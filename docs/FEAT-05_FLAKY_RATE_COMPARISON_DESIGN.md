# FEAT-05 Flaky Rate Comparison Design (Screenshot Diff vs OpenEyes Structured)

> **状态**: 设计（design doc, long-term）
> **创建**: 2026-09-22
> **关联**: docs/INITIATIVES/FEAT-05.md（FEAT-05 long-term 项）、docs/INITIATIVES/FEAT-04-pathB.md（e2e 落地）

## 0. 一句话总结

**SOP**: 同一 baseline matrix 跑两次（一次 screenshot diff，一次 openeyes_structured），对齐失败维度，量化结构化断言替代像素比较的 flaky 率改善。

## 1. 为什么需要这个对比

当前 FEAT-05 把 baseline matrix 的 UI 断言从「像素 diff」迁到「结构化 UIA 断言」(openeyes_structured)。这是 1.0 替代，但要回答「这到底改善了多少？」必须量化。

直觉上：
- 像素 diff 对字体抗锯齿 / 缩放 / 主题色 / 时间戳敏感 → 高 flaky 率
- 结构化 UIA 断言对几何属性 / 主题 / 抗锯齿免疫 → 低 flaky 率
- 但结构化断言可能因窗口结构变化（元素 ID 重排）误判 → 需要看 false failure 维度

## 2. 实验设计

### 2.1 双模式并行

每个 baseline task 在同一轮中跑两次：

`powershell
pwsh -File scripts/Run-BaselineMatrixRealWorkerSmoke.ps1 
    -CaseKeys @('short-001') 
    -Modes @('strong_only') 
    -UiAssertionMode screenshot_diff     # 旧模式
    -ExperimentName feat05-diff-

pwsh -File scripts/Run-BaselineMatrixRealWorkerSmoke.ps1 
    -CaseKeys @('short-001') 
    -Modes @('strong_only') 
    -UiAssertionMode openeyes_structured # 新模式（FEAT-05 落地）
    -ExperimentName feat05-structured-
`

> 注意: -UiAssertionMode=screenshot_diff 尚未实现。需要先在 Run-BaselineMatrixRealWorkerSmoke.ps1 加 diff 分支（用 System.Drawing 比对 PNG bytes，类似 ImageMagick compare -metric AE）。

### 2.2 量化维度

| 维度 | 测量方法 | 期望改善方向 |
|---|---|---|
| **pass_rate** | passed_count / total_runs | openeyes_structured ≥ screenshot_diff |
| **false_failure_rate** | 人工复审 FAIL 但实际正确 / total_runs | openeyes_structured ≤ screenshot_diff |
| **duration_p50** | ui_assertion duration_ms 中位数 | openeyes_structured 更快（无 PNG compare） |
| **duration_p95** | 第 95 百分位 | 显著改善（screenshot p95 经常因超时爆炸） |
| **failure_reason_distribution** | 把 FAIL 分类（结构变化 / 渲染变化 / 内容变化 / 真 bug）| openeyes_structured 主要是「结构变化」，screenshot 主要是「渲染变化」 |

### 2.3 数据落点

每次跑产出一份报告 JSON：

`
.tmp/flaky-comparison/
  feat05-diff-<stamp>.json        # screenshot_diff 模式报告
  feat05-structured-<stamp>.json  # openeyes_structured 模式报告
  summary-<stamp>.json             # 聚合对比报告
`

聚合报告字段：

`json
{
  "schema_version": 1,
  "experiment_pairs": [
    {
      "screenshot_diff_report": "feat05-diff-20260922.json",
      "openeyes_structured_report": "feat05-structured-20260922.json",
      "case_key": "short-001",
      "mode": "strong_only",
      "diff_pass_rate": 0.82,
      "structured_pass_rate": 1.00,
      "diff_p50_ms": 1200,
      "structured_p50_ms": 580,
      "diff_p95_ms": 8500,
      "structured_p95_ms": 1200,
      "false_failure_count_diff": 3,
      "false_failure_count_structured": 0,
      "conclusion": "structured dominates"
    }
  ],
  "overall": {
    "pass_rate_lift": 0.18,
    "duration_p95_drop": 0.86,
    "false_failure_drop": 1.0
  }
}
`

## 3. False Failure 判定口径

最关键的维度。需要明确「false failure」是什么：

### 3.1 判定流程

1. FAIL 发生后调 yes detect 取当前 UIA 元素快照 + yes capture 取当前 PNG
2. 人工 + 启发式判定：
   - **结构变化**（PASS 应 PASS 但 FAIL）：element 位置/automation_id 改了 → structural flake
   - **渲染变化**（PASS 应 PASS 但 FAIL）：截图差分很大但视觉内容合理 → visual flake
   - **真 bug**：FAIL 的确反映了 regression → true failure
3. 把判定结果写回 baseline report 的 manual_review 字段

### 3.2 自动判定启发式（无需人工）

- screenshot_diff FAIL 但 openeyes_structured PASS → 100% 视觉 flake（视觉变化但语义未变）
- openeyes_structured FAIL 但 screenshot_diff PASS → 100% 结构 flake（结构变化但视觉同）
- 两者都 PASS / 都 FAIL → 不算 false failure

这给出一个不需人工的 lower-bound 估计。

## 4. 假设与预期

### 4.1 主假设 H1

openeyes_structured 在真实 baseline matrix 上的 pass_rate 显著高于 screenshot_diff (≥ 10%)。

### 4.2 次假设 H2

duration_p95 至少下降 50%（去掉 PNG compare 这一步）。

### 4.3 反假设 H0（需要证伪）

openeyes_structured 在某些 case（高动态 UI、频繁重排）反而 false failure 更高。需要至少跑 3 case × 3 mode = 9 个组合验证。

## 5. 实施路径（最小切片）

### Phase 1: 基础设施 ✅ 已完成（2026-09-22）

1. ✅ Run-BaselineMatrixRealWorkerSmoke.ps1 加 `[ValidateSet]` 含 screenshot_diff + `UiAssertionBaselineDirectory` 参数 + `Invoke-ScreenshotDiffAssertion` stub 调用
2. ✅ scripts/lib/ScreenshotCompare.ps1 提供 `Compare-ScreenshotSimilarity` pure function（采样 byte-by-byte 容差比较）
3. ✅ scripts/lib/BaselineScreenshotStub.ps1 提供 `Resolve-BaselinePngPath` + `New-ScreenshotDiffStubReport`（phase_1_stub / phase_2_with_baseline 自动切换）
4. ✅ scripts/lib/BaselinePng.ps1 提供 `Save-BaselinePng` + `Get-BaselinePng`（baseline PNG 捕获 + 元数据查询）
5. ✅ 17 + 11 + 20 = 48 个 Phase 1 合同 test 全部 PASS
6. ⏳ `Run-FlakyComparison.ps1`（跑两次 + 聚合输出）尚未建，可在 Phase 2 数据采集时按需
7. ✅ 替换 stub 为真实 `Compare-ScreenshotSimilarity` 调用（Phase 2.5 完成）

### Phase 2: 数据采集（半天，~1 小时 wall time）

跑 short-001 / medium-001 / long-001 × strong_only / small_only / orchestrated = 9 组合 × 2 mode = 18 个 run。collect duration + status。

### Phase 3: 分析（1-2 天）

写 docs/FEAT-05_FLAKY_RATE_COMPARISON_RESULTS.md：
- 表格：每个 case × mode 的 pass_rate / duration / 失败原因
- 结论：H1 / H2 接受 or 拒绝
- 反假设 H0 触发条件
- 决策：是否把 UiAssertionMode=screenshot_diff 标记 deprecated

## 6. 不在范围

- 不同 baseline image 的视觉回归测试方法学本身
- 截图 diff 算法优化（SSIM / perceptual hash）
- CI 上自动跑（本期手动跑 + 写报告即可）

## 7. 引用

- docs/INITIATIVES/FEAT-05.md §当前状态（baseline run 落地记录）
- docs/INITIATIVES/FEAT-04-pathB.md §当前状态（e2e 落地记录）
- scripts/Run-BaselineMatrixRealWorkerSmoke.ps1（参数化 UiAssertionMode）
- scripts/Run-OpenEyesUiAssertion.ps1（结构化断言引擎）
- scripts/Run-OpenEyesTypeVerified.ps1（delivery verification，作为辅助维度）
### Phase 2.5: stub → real comparison 切换 ✅ 已完成（2026-09-22）

1. ✅ scripts/lib/BaselineScreenshotStub.ps1 加 `New-ScreenshotDiffComparisonReport`（PASS/WARN/FAIL 状态机）
2. ✅ Run-BaselineMatrixRealWorkerSmoke.ps1 Invoke-ScreenshotDiffAssertion 完整 wiring：dot-source 三个 lib / `Resolve-BaselinePngPath` 探测 baseline / 三分支 phase_1_stub / phase_2_with_baseline + Phase 2.5 pending note / phase_2_5_real_comparison / 加 `UiAssertionCurrentDirectory` 参数
3. ✅ tests/phase25_smoke.ps1 9/9 PASS（isolated 不依赖 harness）
4. ✅ 注册到 tests/Run-JevPatrolL3Tests.ps1 runner（149 总通过 / 0 失败）
5. ⏳ Run-FlakyComparison.ps1（聚合脚本）仍未建，Phase 2 数据采集时按需建
6. ⏳ Phase 2 数据采集（9 组合 × 2 mode = 18 baseline run）需 harness + baseline PNG 库管理
5. ✅ Run-FlakyComparison.ps1（聚合脚本）落地：跑 screenshot_diff + openeyes_structured 两次 + 聚合输出 summary-<stamp>-flaky-comparison.json；加 `-Cleanup` 清理 > 7d 旧报告
6. ✅ tests/Test-RunFlakyComparison.ps1 16/16 PASS（null report / empty / all-pass / mixed / JSON roundtrip）
