# FEAT-05 - OpenEyes 驱动的 structured UI assertion

> **状态**: 实施中 (in progress)
> **创建**: 2026-09-21
> **维护者**: yangpeng@sobey.com

## 基本信息

| 字段 | 值 |
|---|---|
| 编号 | FEAT-05 |
| 类别 | OpenEyes 借鉴 |
| 优先级 | P2 |
| Phase | Phase 4 |
| 预估工时 | 7 天 |
| 关联文档 | ../OPENEYES_ACCEPTANCE_DEMO_PLAN.md |
| 关联 Bitable 项目方向 | recvvNWh9u3lQZ |

## 状态机

```
候选 (candidate) -- 维护者签字 --> 立项中 -- 开始实施 --> 实施中 -- PR --> 验收中 -- 通过 --> 已关闭
                            |
                            `-- 撤回 --> 已撤销
```

**当前**: 实施中

## 立项门槛 (gating thresholds)

1. scripts/Run-OpenEyesUiAssertion.ps1 生成
2. baseline matrix 新增 ui_assertion_mode=openeyes_structured
3. /console/ 看到 UI assertion 历史

## 当前状态

2026-09-22：FEAT-05 最小切片推进到实施中。新增 `scripts/Run-OpenEyesUiAssertion.ps1`，用 `eyes windows list -> eyes detect -> eyes capture` 生成结构化断言；决策只使用 `name / automation_id / control_type / element_count / visible / enabled`，截图仅作 evidence，不做像素 diff。脚本支持 `OutputJsonPath` 输出 `schema_version=1 / assertion_mode=openeyes_structured / status / checks / capture / duration_ms`。

实测：隔离 PowerShell WinForms 探针（8 个 UIA 元素）正向 PASS，6/6 checks 通过（title、minimum_element_count、element_name_contains、element_automation_id、element_control_type、capture_file），耗时 4.268s，capture 8308 bytes。负向 `MissingAutomationId` 返回 FAIL 且 exit=1，对应 `element_automation_id` check FAIL。2026-09-22：baseline matrix 已接入显式 `UiAssertionMode=none|openeyes_structured`；默认 `none` 不改现有行为。opt-in 模式会在触网前校验 `UiAssertionAppTitleContains` 与 assertion script，按 task 写 `ui_assertion_reports`、计算 `ui_assertion_passed`，断言失败即 baseline fail-fast。

2026-09-22：console 历史透传已落地。`TaskHandler.java` 新增 `GET /api/v1/tasks/{id}/ui_assertion` 只读 endpoint，从 `.tmp/openeyes-ui-assertion/{dir}/{taskId}.json` 读取报告（路径归一化 + 白名单校验防穿越），console `app.js` 加载并渲染 Structured UI Assertion 卡（PASS/FAIL badge、通过/总数 checks、窗口 title）。剩余是补一次真实 harness baseline run 和 flaky 率对照。

2026-09-22：真实 harness baseline run 验收通过。`Run-BaselineMatrixRealWorkerSmoke.ps1` 修复 assertion 调用从数组 positional splat 改为 hashtable named splat（修复 `-WindowIndex` 误吃 title 的 bug）。端到端验证：harness 启动 → 隔离 WinForms 目标窗口（ACH-OpenEyes-Baseline-Target，6 控件 11 UIA 元素）→ 创建 short-001/strong_only task → task 终态后自动执行 structured assertion → 10/10 checks PASS → `ui_assertion_passed=true`。Report: `.tmp/baseline-feat05-single.json`。FEAT-05 三项立项门槛全部满足。

2026-09-22：flaky rate comparison 设计文档落地 `docs/FEAT-05_FLAKY_RATE_COMPARISON_DESIGN.md`。设计同一 baseline matrix 跑 screenshot_diff 与 openeyes_structured 双模式，量化 pass_rate / duration_p50/p95 / false_failure_rate / failure_reason_distribution 四维度。Phase 1 需先给 `Run-BaselineMatrixRealWorkerSmoke.ps1` 加 `screenshot_diff` 分支。

## blocker

(无)

## sign-off checklist

- [ ] 维护者确认立项门槛全部满足
- [x] 凭据纪律: 本条零外部凭据；OpenEyes CLI 本地工作；TYPESAFE_API_KEY 不适用
- [x] CONTRIBUTING.md 该编号状态已在 INDEX.md 同步 (实施中 baseline run 已通过)
- [x] Bitable 项目方向表 recvvNWh9u3lQZ 状态同步 (2026-09-22 通过 lark-cli base +record-batch-update --as user 同步)
- [ ] 实施 PR 链接记录到本 card (用户未请求 commit/PR)

## 维护约定

- 状态切换改本文件的 当前 字段 + 文末当前状态小节
- INDEX.md 通过本文件路径聚合
- 关联 Bitable 通过软引用 (不建双向 schema 依赖)

## 参考

- 关联文档: ../OPENEYES_ACCEPTANCE_DEMO_PLAN.md
- 设计依据: docs/JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md
- 本地落仓决策依据: 2026-09-21 Codex session (落仓优于新建 Bitable 表, 等 schema 稳定后迁)
2026-09-22：Phase 1 实施：`Run-BaselineMatrixRealWorkerSmoke.ps1` 给 `UiAssertionMode` 加 `[ValidateSet('none','openeyes_structured','screenshot_diff')]` + `UiAssertionBaselineDirectory` 参数 + `Invoke-ScreenshotDiffAssertion` stub 函数。Phase 2 准备工作：`scripts/lib/ScreenshotCompare.ps1` + `BaselineScreenshotStub.ps1` (3 pure functions) + `BaselinePng.ps1` (2 pure functions)。Phase 2.5 wiring 完成：`Invoke-ScreenshotDiffAssertion` 完整三分支（phase_1_stub / phase_2_with_baseline / phase_2_5_real_comparison），加 `UiAssertionCurrentDirectory` 参数。tests/phase25_smoke.ps1 9/9 PASS。9 套件 / 149 总通过 / 0 失败。
