# 复杂需求三段式协作方案（Design -> Implement -> Verify）

> 文档类型：方案计划（`*_PLAN.md`）。本文档先固化“复杂需求先出方案、再实施、再闭环验收”的任务组织方式，当前阶段不引入新自动编排代码。

## 1. 背景

针对稍复杂的真实需求，当前有一个明确的协作想法：

1. 先由 `codex / deveco` 落方案文档（`*.md`）。
2. 再由 `codebuddy / trae` 按方案实施。
3. 最后再由 `codex / deveco` 按方案核对业务是否闭环，并在必要时补修。

这条思路和仓库现有方向是一致的：先把问题、方案、runbook、验证证据串成闭环，而不是直接把任务扔给单一 worker 一次性完成。

但要把它写成项目内正式流程，必须先按源码核清当前 worker 的真实可执行边界，避免把“想要的角色分工”误写成“已经自动化支持的能力”。

## 2. 当前源码核对结论

以下结论来自当前仓库代码与文档，不是推测：

### 2.1 任务链路已经支持分阶段拆分

- `POST /api/v1/tasks` 已支持 `parent_task_id`，可把一个复杂需求拆成父任务 + 多个子任务。
- 若只传 `parent_task_id` 不传 `session_id`，子任务会继承父任务 session。
- `pause / resume / handoff / human_gate / packet` 已是现成控制面能力，适合承载阶段间切换和人工 gate。
- `live_flow / judgment_trace / experiment_run / tool_trace` 已能作为每个阶段的观测面。

这意味着“方案任务 -> 实施任务 -> 验证任务”不必挤在一个 task 里，可以明确拆成一条 task chain。

### 2.2 当前 worker 真实状态不完全对称

| Worker | 当前接通状态 | 本地工作区能力 | 适合的第一版角色 |
|--------|--------------|----------------|------------------|
| `codex` | 已接通；`provider_app_server` | `local_workspace_access=true` | 方案、验证、补修 |
| `deveco` | 已接通；专属 `DevecoProtocol` | `local_workspace_access=true` | 方案、验证、补修 |
| `codebuddy` | 已接通；专属 `CodeBuddyProtocol` | `local_workspace_access=false`，`workspace_access_mode=executor_not_supported` | 显式实施位，不建议默认自动实施 |
| `trae` | provider / worker 已注册，dispatch probe 已有 | `local_workspace_access=false`，且当前无专属 protocol / executor 命令计划 | 用户调用实施位，当前不应视为 harness 自动实施位 |

关键边界：

- `codebuddy` 虽然已经接通协议和 parser，但当前 worker metadata 仍明确标记本地工作区访问未收口，因此不宜直接写成“默认自动改仓实施 worker”。
- `trae` 当前在 `BuiltinAgentProviders`、`WorkerRegistry`、`LocalCliAgentProvider` 中有注册与探针，但 `ProviderProtocolRegistry` 未注册 `trae` protocol，`ProviderCliWorkerExecutor` 也没有 `trae` 命令计划；它现在更接近“已发现、可探测、但未自动执行闭环”的状态。

### 2.3 文档优先闭环已经是仓库约束

- `AGENTS.md` 已明确：调研、排查、方案设计、验收整理默认先沉淀到仓库文档。
- `docs/README.md` 已明确：计划应尽量绑定 runbook、focused test、probe、execution record。

所以这条新流程的第一版，不应直接追求“全自动多 agent DAG”，而应先把文档与任务合同收紧。

## 3. 第一版设计决策

### 3.1 采用“三段式任务链”，不采用“单任务内隐式多 agent 轮转”

第一版推荐用：

```text
父任务（复杂需求总任务）
  -> 子任务 A：Design
  -> 子任务 B：Implement
  -> 子任务 C：Verify
```

而不是把全部阶段塞进一个 task 里靠隐式 handoff 反复切 worker。

原因：

- 现有 `parent_task_id` 已能稳定表达阶段关系。
- 方案文档、实施 diff、验证结论本来就是三类不同产物。
- 把阶段拆开后，`packet / live_flow / experiment_run` 更容易对齐到具体阶段。
- 当前 `trae` 还不能自动执行，拆阶段更容易容纳“用户手工调用后再回填结果”的半自动流程。

### 3.2 worker 角色优先级按“现实能力”而不是“理想愿景”落地

建议角色矩阵：

| 阶段 | 首选 worker | 允许模式 | 第一版说明 |
|------|-------------|----------|------------|
| `design` | `codex` 优先，`deveco` 兜底 | 自动或显式指派 | 两者都已具备可用工作区访问与执行闭环 |
| `implement` | `codebuddy` 优先，`trae` 次选 | 显式指派或用户调用 | 保留用户设想，但先不把两者写成默认自动实施位 |
| `verify` | `codex` 优先，`deveco` 兜底 | 自动或显式指派 | 适合读取方案、核对实现、补测试与补修 |

补充约束：

- `trae` 当前只能作为“用户调用实施位”进入方案，不应作为 harness 自动路由的默认实现 worker。
- `codebuddy` 当前更适合“显式 implementation worker”，而不是在未补工作区能力验证前进入默认自动实施优先级。
- 如果某次实施必须完全走 harness 自动执行，第一版仍应允许回退到 `codex / deveco` 承担实施。

这不是否定 `codebuddy / trae` 的实施位，而是把“流程设计目标”和“当前自动化能力”拆开。

## 4. 三段式流程定义

## 4.1 Stage A: Design

目标：

- 先把复杂需求收成一份可执行方案文档。
- 明确边界、改动范围、验收标准、验证命令、风险点。

建议：

- 子任务类型：`coding`
- `assigned_worker`：`codex` 或 `deveco`
- `auto_start=false`，先看 `/select_worker` 再 `/continue`
- 产物必须落到 `docs/*.md`

方案文档最低内容：

1. 背景 / 原问题
2. 真实现状
3. 拟议改动范围
4. 受影响模块 / 文件
5. 验收标准
6. focused test / probe / runbook 入口
7. 风险与回退口径

建议 metadata：

```json
{
  "workflow_template": "design_implement_verify",
  "workflow_stage": "design",
  "plan_owner_worker": "codex",
  "plan_doc_path": "docs/<topic>_PLAN.md",
  "acceptance_gate": "plan_doc_required"
}
```

## 4.2 Stage B: Implement

目标：

- 按方案实施，不在这一阶段重新发散设计边界。
- 对照方案产出最小代码改动、focused tests、未完成项。

建议：

- 子任务类型：`coding`
- 首选 `codebuddy`，`trae` 作为用户调用模式
- 若用 `trae`，当前按“用户调用 -> 回填结果 -> 再进入 verify”处理
- 若实施中发现方案不够落地，应 `pause` 或回 Design，不要静默改方案

实施阶段最低输入合同：

- `plan_doc_path`
- `reference_paths`
- `write_scope`
- `validation_commands`
- `acceptance_criteria`

建议 metadata：

```json
{
  "workflow_template": "design_implement_verify",
  "workflow_stage": "implement",
  "plan_doc_path": "docs/<topic>_PLAN.md",
  "implementation_mode": "explicit_worker_or_user_call",
  "implementation_worker": "codebuddy",
  "implementation_requires_manual_gate": true
}
```

当前约束下的具体口径：

- `codebuddy`：先按显式 worker 使用，不抢默认自动实施优先级。
- `trae`：先按 `manual_user_call` 使用，不进入默认自动执行路径。
- 如果实施完全依赖自动改仓，第一版允许回退 `codex / deveco` 执行实施，但验证阶段仍应回到 `codex / deveco` 独立复核。

## 4.3 Stage C: Verify

目标：

- 用独立于实施阶段的视角检查“方案是否被落实”“业务是否闭环”。
- 必要时直接补小修，或明确回退到 Design / Implement 哪一段。

建议：

- 子任务类型：`coding`
- `assigned_worker`：`codex` 或 `deveco`
- 强制带 `plan_doc_path`、`validation_commands`、实施产物引用

验证阶段最少检查：

1. 方案里的验收项是否被一一覆盖
2. focused tests / probes 是否真实执行
3. 运行时观测面是否闭环
4. 是否仍有未解决风险或偏差

建议 metadata：

```json
{
  "workflow_template": "design_implement_verify",
  "workflow_stage": "verify",
  "plan_doc_path": "docs/<topic>_PLAN.md",
  "verification_worker": "codex",
  "verification_can_patch": true,
  "acceptance_gate": "business_closure"
}
```

如果验证失败，回流规则建议固定：

- 方案缺口 -> 回 `design`
- 实现偏差 -> 回 `implement`
- 仅剩小修 -> 允许 verify 直接补修一次，再重跑验证

## 5. 推荐任务组织方式

## 5.1 用父任务绑定整个复杂需求

父任务负责：

- 记录总体目标
- 串起 `design / implement / verify` 三个子任务
- 汇总阶段性结论和最终闭环状态

建议父任务 metadata：

```json
{
  "workflow_template": "design_implement_verify",
  "task_family": "complex_requirement",
  "requires_stage_chain": true
}
```

## 5.2 子任务明确写阶段，不靠标题猜

建议每个子任务都显式带：

- `workflow_stage`
- `plan_doc_path`
- `stage_owner_worker`
- `acceptance_gate`

这样后续 `live_flow`、`experiment_run`、消息投影、控制台聚合时，不需要从自然语言猜“这轮是在出方案还是在做验证”。

## 5.3 第一版不要求自动生成子任务 DAG

当前更适合的落地方式是：

1. 人工或 façade 先创建父任务
2. 按阶段显式创建子任务
3. 用 `parent_task_id` 串起来
4. 每轮执行结束后把关键结果写回文档或 `STATE.md`

先保证流程清晰，再考虑是否值得补“按模板自动生成三段式子任务链”。

## 6. 运行与观测建议

每个阶段至少保留：

- `/api/v1/tasks/{id}/select_worker`
- `/api/v1/tasks/{id}/live_flow`
- `/api/v1/tasks/{id}/judgment_trace`
- `/api/v1/tasks/{id}/experiment_run`

涉及恢复或跨阶段切换时，再保留：

- `/api/v1/tasks/{id}/packet`
- `/api/v1/checkpoints/{taskId}`
- `/api/v1/tasks/{id}/tool_trace`

阶段 gate 建议：

- Design 完成前，不开 Implement
- Implement 结果未回写，不开 Verify
- Verify 未给出“已闭环 / 未闭环 + 原因”，父任务不关闭

## 7. 当前风险

| 风险 | 当前状态 | 建议口径 |
|------|----------|----------|
| `trae` 被误写成可自动实施 | 真实并未接通 protocol / executor 命令计划 | 文档里明确为 `manual_user_call` |
| `codebuddy` 被误写成默认自动改仓 worker | protocol 已接通，但工作区能力 metadata 未收口 | 第一版只写成显式 implementation worker |
| 方案文档质量不稳定 | 若无固定结构，实施阶段容易漂移 | Design 阶段强制落 `plan_doc_path` 与验收项 |
| 实施后无人独立复核 | 单 worker 自证容易把“写完”当“闭环” | Verify 阶段由 `codex / deveco` 独立复核 |
| 阶段边界只留在对话里 | 中断后容易丢上下文 | 每阶段至少写回 `docs/*.md` 或 `STATE.md` |

## 8. 第一版后续动作

如果要把这套方案从“文档约定”继续推进到“产品化入口”，建议按下面顺序做：

1. 先跑 1 到 2 个真实复杂需求试点，验证三段式链路是否顺手。
2. 补一个 task metadata 小合同，固定 `workflow_template / workflow_stage / plan_doc_path`。
3. 收紧 worker 路由口径：
   - `trae` 默认降为 manual-only / suggest-only
   - `codebuddy` 在未补 workspace 能力验证前不提高自动实施优先级
4. 再评估是否值得补“创建父任务时自动生成三段式子任务链”。

## 9. 结论

这条想法适合落成项目内正式方案，但第一版必须坚持一个现实边界：

- 流程上，可以设计成 `codex/deveco -> codebuddy/trae -> codex/deveco`
- 自动化上，当前只能把 `codex / deveco` 视为稳定的方案与验证位
- `codebuddy` 先作为显式实施位
- `trae` 先作为用户调用实施位

也就是说，第一版先把“阶段链”和“文档合同”做实，再决定哪些 worker 进入默认自动执行闭环。
