# Jev × 飞书巡检 Phase 5 L5 schema 增量设计稿

> 目的：仅当 Phase 4 active routing 启用门槛（TP+TN≥75% 且 uncertainty band 覆核≥60%）达成时，Bitable 项目方向表才需要补 5 个 typeable 字段。
> 本文是设计稿；不直接动 Bitable schema。

## 1. 增量动机

当前 `state/jev-shadow/*.json` 已经能保存 probability / action / latency / error / context / observed 全部信息。但这些 sidecar 与 Bitable 项目方向行是两套坐标系，每次都要 join。Phase 5 让 Bitable 行直接带上一条「最近一次 Jev 评分」字段，避免每次 round 都重新解析 sidecar 目录。

## 2. 字段清单（仅 typeable 字段）

| 字段名 | 类型 | 含义 | 默认值 |
|---|---|---|---|
| `最近一次 Jev 评分` | number (0–1) | 上次 shadow sidecar 的 probability | 空 |
| `最近一次 Jev 决策` | select (KEEP_VERBATIM / TRUNCATE_HEAD / HUMAN_REVIEW / FALLBACK) | Phase 4 decision gate 路由结果 | 空 |
| `最近一次 Jev 时间` | datetime | 上次 sidecar 的 generated_at | 空 |
| `Jev 历史 TP/TN` | number | 累计 TP+TN 命中数（维护者覆核后写入） | 0 |
| `Jev 历史 FP/FN` | number | 累计 FP+FN 误判数 | 0 |

约束：

- 所有字段 readonly 视角：Bitable 自动写入由 sync-back 路径执行，落 decisions 的 storage 仍是 sidecar；只有当 Phase 4 active routing 切到 enabled 时，Bitable 行才被动同步。
- 写回采用"最小侵入"：每次只覆写被改字段，其他字段保留。
- 新增的"最近一次 Jev 决策"字段使用已有的 `当前阶段` 同款 select 控件，避免新建枚举造成 schema 膨胀。

## 3. 现有字段是否调整

不需要动现有 18 个字段。schema 只加 5 个新字段，并保留现有 `当前阶段`/`下一步`/`外部成就` 的语义。

## 4. 维护者启用流程

1. 跑 ≥30 条真实 shadow 样本。
2. `Run-BuildJevShadowEval.ps1` 给出 hit_rate ≥ 0.75。
3. 维护者人工评审 uncertainty band 内 ≥60% 样本。
4. 维护者拍板：扩 Bitable schema（一次性 5 个字段）。
5. `BitAbleAdapter` 加 `-WriteJevHistory` 开关把上述 5 字段回写（默认关闭，与现有 `sync_back && -SyncBack` 双闸门并列）。
6. 开启后默认样本 eval 通过 ≥95% 才视为 Phase 5 完成。

## 5. 与 Phase 4 关联

- Phase 4 active routing 默认 disabled；切到 enabled 后才能触发 schema 增量。
- 关闭 active routing 时，所有 5 字段保留空值，不影响现有派工。
- 真 key 仍走临时进程环境变量，schema 写回不携带 key。

## 6. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| schema 改动被拒绝 | Phase 5 拆分提交，可独立回滚 |
| 自动写回污染人工 review | sync_back 双闸门 + `human_label` 必填 |
| 字段膨胀导致 Bitable 性能下降 | 仅 5 字段且字段类型最小 |
| 维护者不愿公开评分 | 字段都是 typeable，可选择性回填 |

## 7. 当前依据

- Phase 4 controller + sidecar + eval 已经全部落地。
- 12 样本（11 scaffold dry-run + 1 feishu OpenEyes）全部落入 uncertainty band；说明当前样本分布下 Jev 几乎从不做 auto 决策，schema 增量价值在于让 Bitable 行能直接展示"哪条项目需要人审"。
- 评测 + digest + decision 三层闭环都已经跑通；扩展字段不会改变已有评估方法。

## 8. 下一步

- 把本文随 plan 一起在下一轮 Bitable 同步里贴到 `recvvP9I0XrgTJ` 的"相关文档"。
- 若维护者拍板启用 Phase 5 → 起 Phase 5 实施 initiative（独立子档），Bitable schema 增量仍走维护者 review。
- Phase 5 不在当前轮自动执行；任何 schema 改动需要维护者确认。