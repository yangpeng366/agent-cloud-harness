# CCX + Harness 真实研发案例调试执行记录

> 执行日期：2026-07-25
> 验证对象：重建的 `Run-HarnessWithCcx.ps1` + harness -> CCX -> codex -> LLM 端到端 loop
> 前置脚本：`scripts/Run-CcxIntegrationPrecheck.ps1`（ALL PASS）
> 主题归属：evaluation（real-worker smoke / dated execution evidence）

## 执行环境

- CCX 网关：`http://127.0.0.1:3688`（v2.9.37，168 models）
- Harness：`http://localhost:9090`（0.2.0），经重建的 `scripts/Run-HarnessWithCcx.ps1` 启动
- JAR：`agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar`（2026-07-22 构建，早于配置驱动 worker lane）
- Bearer token：自动从 codex `config.toml` 的 `[model_providers.ccx].experimental_bearer_token` 读取
- 模型路由：`codex` -> CCX 自动路由到 `glm-4-flash`
- Java：JDK 21.0.9

## 调试方法

先跑 CCX precheck，再用重建脚本后台启动 harness，投喂 3 个真实研发案例，观察 `goal -> plan -> execute -> judge -> decide` loop，并**独立验证产物文件是否落地**（不只看 `status=done`）。

## 案例

### Case 1：research / openclaw-native —— 伪完成

| 项 | 值 |
|------|------|
| task | `task_d4d08ccd86354c6b` |
| task_type | research |
| intent | 读 `docs/ARCHITECTURE.md`，写一句中文摘要到 `.tmp\rnd-arch-summary.txt` |
| assigned_worker | openclaw-native（task_type=research 自动 pin） |
| 结果 | auto_start 后卡 `control_node=scheduler` / `orchestration_stage=plan_pending`；`POST /continue` 后推进到 `done`/`end` |
| 产物 | `.tmp\rnd-arch-summary.txt` **未生成** |
| summary | “架构摘要已概括完成，待执行 write_file” —— 只规划未执行 |
| 结论 | openclaw-native 只产出 plan/next_step，不执行 write_file 工具；loop 仍按 worker 声称把 subgoal 标 done |

### Case 2：coding / codex —— 真成功（文件创建）

| 项 | 值 |
|------|------|
| task | `task_a28910ada0cc4871` |
| task_type | coding |
| intent | 在 `.tmp` 创建 `rnd-hello.py`，内容 `print('hello from harness rnd case')` |
| assigned_worker | codex（task_type=coding pin，strong tier） |
| 结果 | auto_start 单轮 `done`/`end`，约 21s |
| 产物 | `.tmp\rnd-hello.py` **已生成**，内容完全正确 |
| progress | 1/1 subgoals done |
| 结论 | codex 经 CCX 真实执行工具，产物落地 |

### Case 3：coding / codex —— 真成功（代码理解）

| 项 | 值 |
|------|------|
| task | `task_e59e4c63ce7642a9` |
| task_type | coding |
| intent | 读 `src/main/java/com/agentcloud/llm/LlmConfig.java`，判断配置来源（环境变量 vs 系统属性），写结论到 `.tmp\rnd-llmconfig-finding.txt` |
| assigned_worker | codex |
| 结果 | `done`/`end`，约 39s，2 次 tool invocation，`duration_ms=32620` |
| 产物 | `.tmp\rnd-llmconfig-finding.txt` **已生成**，内容“LlmConfig 通过 `System.getenv()` 读取 OPENAI_BASE_URL/OPENAI_API_KEY……未使用系统属性 `System.getProperty()`” —— 与源码事实一致 |
| judgment | execution=continue（transient retry）；completion=partially_done/low（检测到 codex_core transient error）；task 仍 `done`（产物已落地） |
| proof | `proof=tool:call_d56f0e51b7eb41bc9dbf6291, tool:call_958cb3e858564cd39f5b20b5` |
| 结论 | codex 经 CCX->glm-4-flash 能真实读文件 + 准确分析 + 写产物 |

## 关键发现

1. **task_type 驱动 worker pinning**：research -> openclaw-native，coding -> codex（strong）。需工具执行的任务必须用 `task_type=coding`，否则被 pin 到不执行工具的 worker。
2. **openclaw-native 伪完成**：只规划不执行，loop 仍标 done —— 验收必须独立核对产物，不能只看 `status`。
3. **codex/CCX 路径可靠**：文件创建 + 代码理解均准确落地，约 21-39s/任务。
4. **codex_core 流式噪声**：codex CLI 经 CCX->glm-4-flash 间歇输出 `ReasoningSummaryPartAdded without active item` ERROR，污染 task `summary`；judgment 据此判 `partially_done/low`，但实际工具执行成功。属 codex CLI streaming 协议层问题，非 harness bug。
5. **API 响应包装**：`/sessions`、`/tasks` 等返回 `{success,code,message,data}`，实体在 `.data`；`/api/v1/health` 例外（裸对象）。客户端必须取 `.data`。
6. **preflight warmup 延迟启动**：18 worker 探测约 13s，`health` 在 warmup 完成后才可用；测试可用 `-DisableDispatchPreflightWarmup` 跳过。
7. **auto_start 单轮 + /continue**：简单任务 auto_start 单轮即 done；卡 `scheduler/plan_pending` 时 `POST /tasks/{id}/continue` 推进。`/continue` HTTP 可能 504，服务端仍跑完，需轮询 `GET /tasks/{id}`。
8. **当前 JAR 无两 lane**：2026-07-22 JAR 用 BuiltinAgentProviders，候选 worker=`[codex, codex-openai, codex-xfyun, codex-deepseek]`；`codex-main`/`codex-free` 需 2026-07-23+ 配置驱动代码 + `harness-config.yml`，需重建 JAR 才能测两 lane。
9. **pi/codebuddy/deveco 探测超时**：启动日志显示这 3 个 worker 命令探测超时，被标记不可用 600s —— 印证“不走 pi”的现实约束。

## 最佳实践（操作清单）

### 启动

1. 先 `Run-CcxIntegrationPrecheck.ps1` 确认 CCX health + models + completion 全 PASS
2. CCX Desktop 先起；用 `Run-HarnessWithCcx.ps1 -Port 9090 -Background` 启动（token 自动读 `config.toml`）
3. 轮询 `GET /api/v1/health` 直到 `llm.available=true`（preflight 完成后）；急用加 `-DisableDispatchPreflightWarmup`

### 投喂任务

4. `task_type` 按 capability 选：需工具执行/代码 -> `coding`（codex）；纯分析展示 -> `research`（openclaw-native，但不保证产物落地）
5. `intent` 要可验证（指定产物路径），验收时独立核对文件，不只看 `status=done`
6. `auto_start=true` 后轮询 `GET /tasks/{id}`；卡 `scheduler/plan_pending` 则 `POST /tasks/{id}/continue`
7. 客户端解析响应取 `.data`

### 观测 / 调试

8. `GET /tasks/{id}/judgment_trace` 看 execution/completion judgment + `recommended_action` + `proof`
9. `GET /tasks/{id}/tool_trace` 看 tool invocation
10. `GET /tasks/{id}/live_flow` 聚合诊断
11. `.tmp/provider-runs/codex/{taskId}/` 下有 `prompt.txt` / `events.jsonl` / `last_message.md` 排障

### 已知坑

12. codex_core “ReasoningSummary” ERROR 噪声会进 `summary` —— 不代表失败，看 `tool_trace` / 产物
13. `completion_judgment=partially_done/low` 但产物已落地时 task 仍可能 `done` —— 以产物为准
14. 两 lane（`codex-main`/`codex-free`）需重建 JAR（2026-07-23+ 代码）+ `harness-config.yml`

## 后续

- 重建 JAR 测两 lane 路由（`codex-main` 付费 vs `codex-free` 免费）
- 调查 codex_core streaming ERROR 根因（是否 CCX responses->chat 转换引入）
- openclaw-native 伪完成：考虑 completion judgment 对“声称 done 但无 tool proof”的兜底校验

## 证据产物

- `.tmp\rnd-hello.py`（Case 2）
- `.tmp\rnd-llmconfig-finding.txt`（Case 3）
- `.tmp\harness-ccx.out.log`（harness 启动 + 执行日志）
- `.tmp\provider-runs\codex\{taskId}\`（per-run prompt/events/last_message）
## 优化方案（基于调试结果）

### 根因分析

**问题 1：openclaw-native 伪完成（Case 1）**

`ControlNodeGraph.autoUpdateSubgoalStatus` 在 `executionStatus=="completed"` 时**无条件**把当前 in_progress subgoal 标为 `done`，只看 worker 声称的执行状态，不校验是否真有 tool/artifact 证据：

- `executionCompleted = "completed".equals(executionResult.executionStatus())` -> 直接 `migrateFirstInProgressSubgoal(raw, "done")`
- openclaw-native 对"写文件"类任务只产出 plan/next_step，不执行 write_file，却返回 `completed` -> subgoal 被误标 done -> `resolveGoalProgressAction` 返回 `done` -> `resolveAction` 返回 `done` -> task 伪完成。

`resolveAction` 本身已有正确路由（open subgoals + execution done -> `checkpoint`），只要 `subgoal_status` 准确就不会误标 done。所以根因在 `autoUpdateSubgoalStatus` 的"无证据即标 done"。

**问题 2：codex_core ERROR 噪声污染 summary（Case 2/3）**

codex CLI 经 CCX->glm-4-flash 间歇输出 `ReasoningSummaryPartAdded without active item` ERROR，进入 `latest_output`/`summary`。根因在 codex CLI streaming 协议层（外部），不在 harness。harness 侧做行级过滤属于贴面补丁，不治本，故**不在本轮修复**，留作后续（需在 codex CLI / CCX responses->chat 转换层定位）。

### 修复设计（问题 1）

在 `autoUpdateSubgoalStatus` 迁移前加 false-done guardrail：

- `expectsToolExecution(task)`：goal/intent 含 写入/创建/修改/删除/运行/执行命令/create/write/modify/delete/run/mkdir 等动作关键词 -> 任务期望工具执行。
- `hasExecutionProof(executionResult)`：`producedArtifact` || `artifactContent` 非空 || `evidenceRefs` 非空 || `tool_invocation_ids` 非空 || metadata `produced_artifact`。
- 当 `executionCompleted && expectsToolExecution && !hasExecutionProof`：**不标 done**，保持 subgoal 为 in_progress，写 `subgoal_judgment_source=evidence_gap_no_tool_proof` 并 warn 日志，早返回。
- 后续 `resolveGoalProgressAction` 看到 open subgoal -> `resolveAction` 路由到 `checkpoint`（存进度不标完成），交由 loop 重试或 handoff。

**不回归保证**：guard 只在 goal/intent 含动作关键词时触发。现有 `GoalProgressAutoUpdateTest` 用例 goal=null/title="demo"（无动作关键词），不受影响。纯文本任务（"解释 X"）无动作关键词 -> 仍按 completed 标 done。

## 优化验证

### 单元测试

新增 2 个用例到 `GoalProgressAutoUpdateTest`，覆盖 guard 两侧：

- `completedExecutionWithActionGoalButNoProofKeepsSubgoalInProgress`：目标含"写入" + completed + 无 proof -> subgoal 保持 `in_progress`，写 `subgoal_judgment_source=evidence_gap_no_tool_proof`，progress_summary 不变。
- `completedExecutionWithActionGoalAndProofMarksSubgoalDone`：目标含"写入" + completed + producedArtifact=true -> 正常标 `done`（guard 不误伤）。

回归结果（JDK 21，`mvn -Dtest=... test`）：

| 测试集 | Tests run | Failures | Errors |
|--------|-----------|----------|--------|
| `GoalProgressAutoUpdateTest` | 11（9 旧 + 2 新） | 0 | 0 |
| 8 个 control-graph/goal/judgment 类合计 | 109 | 0 | 0 |

guard WARN 日志按预期触发：`[Subgoal Guard] task=... execution completed but no tool/artifact proof for action-expecting goal; kept subgoal in_progress`。

### 运行时验证

见下（重建 JAR 后重跑 Case 1）。

### 运行时验证（false-done guard 重建后重跑）

重建 JAR（`target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar`，2026-07-25 22:38）后重跑"research + 写本地文件"案例（新产物 `.tmp\rnd-arch-summary2.txt`）：

- task=`task_dbc933d453f04cf5`，终态 `waiting_human`（不再伪完成）
- `subgoal_judgment_source=evidence_gap_no_tool_proof`，`progress_summary=0/1 subgoals done`
- guard 按预期触发：execution completed 但无 tool/artifact proof -> subgoal 保持 in_progress

重要修正：首轮判定"产物未生成"是误读，复核发现 `.tmp\rnd-arch-summary2.txt` **确实存在**，`tool_trace` 显示 `write_file` 经 openclaw-native 成功执行。即 guard 仍正确有用，但暴露出更深层问题：

- openclaw-native 最终会执行工具，但整体收口质量下降，task 仍退化到 `waiting_human`
- "带本地写文件意图的 research 任务"不应停留在 openclaw-native 路径，应按 coding 路由到 codex

由此引出下节路由优化。

## 路由优化（问题 1 深化：research+本地写入应走 codex）

### 发现

false-done guard 只能阻止"误标 done"，不能改善 openclaw-native 对写文件任务收口不稳的本质。Case 1 经 guard 后从"伪完成"变成"卡 waiting_human"，仍是劣化路径。根因在路由层：`task_type=research` + `preferred_worker=openclaw-native` 的 pin 把写文件任务锁死在 tool-suggest 型 worker 上。

### 根因

`WorkerRouter.selectWorker` 的 pinned 分支只校验 workspace 访问，**没有**像 `selectWorkerWithoutPinned` 那样执行 `auto_route_task_types` 契约：

- `openclaw-native` 声明 `auto_route_task_types=[browser,doc,message,search,reading]`，不含 `coding`
- 但其 `execution_backend=tool_aware`，注册时 `effectiveToolCapabilities` 补了 host tools、`effectiveToolScope` 补了 cwd，导致 `hasLocalWorkspaceAccess(openclaw-native)=true`
- pinned 分支的 `pinnedLacksWorkspaceAccess=false` -> pin 生效 -> 选 openclaw-native，绕过契约

### 修复设计

两层联动，对齐非 pinned 路径既有契约：

1. **taskType 提升**（`WorkerRouter.normalizeTaskTypeForRouting`）：`research` 任务若 `expectsWorkspaceMutation`（goal/intent/title + workspace 元数据同时命中"写入/创建/修改/删除/write/create/..."动作词与"文件/file/.tmp/.md/src/..."文件信号），提升为 `coding`，使 codex 进入候选集并暴露在 `route.taskType()`。
2. **pinned 契约兜底**（`selectWorker` pinned 分支）：新增 `pinnedNotAllowedForTaskType = shouldApplyAutoRouteTaskTypeContract(taskType) && !autoRouteAllowedForTaskType(pinned, taskType)`，与 workspace 访问校验并列。pinned worker 不满足 taskType 契约时**绕过 pin**，回退 `selectWorkerWithoutPinned`，fallbackReason 写明 `not allowed for taskType=coding by auto_route_task_types contract`。

效果：research+本地写入 -> 提升为 coding -> openclaw-native 因不声明 coding 被 pin 绕过 -> 回退到 codex 等编码 worker。对合法 pin（如 pin codex 做 coding）无影响：codex 声明 `auto_route_task_types=[coding,reading,ops]`，契约通过。

### 验证

新增 `WorkerRouterRouteTraceTest.researchTaskWithWorkspaceMutationBypassesPinnedOpenclawAndRoutesToCodex`：research + `preferred_worker=openclaw-native` + 写文件 intent + workspace_root -> 断言 `taskType=coding`、`selectedWorker=codex`、`preferredWorkerHint=openclaw-native`、fallbackReason 含 `auto_route_task_types contract` 与 `taskType=coding`。

回归（JDK 21）：

| 测试集 | Tests run | Failures | Errors |
|--------|-----------|----------|--------|
| `WorkerRouterRouteTraceTest` | 33 | 0 | 0 |
| `GoalProgressAutoUpdateTest` | 11 | 0 | 0 |
| `ControlNodeGraphActionResolutionTest` | 43 | 0 | 0 |
| `ControlNodeGraphOrchestrationFlowTest` | 17 | 0 | 0 |
| `ControlNodeGraphDecideGoalProgressPriorityTest` | 10 | 0 | 0 |
| `AdvisoryHandoffTest` / `HandoffDepthLimitTest` / `TaskPartialStatusTest` / `LlmSubgoalJudgmentServiceTest` | 6+5+6+11 | 0 | 0 |
| 上述合计 | 142 | 0 | 0 |

仅剩 2 个历史失败（`WorkerExecutorRouterProviderNativeTest.explicitProviderBackendWithoutExecutorSupportFailsFastInsteadOfFallingBackToDefault`、`WorkerPromptHeaderBuilderTest.taskHeaderOmitsDuplicateGoalAndIntent`），STATE 07-23 已标注为非本轮改动引入，未触碰。

### 后续

- 重建 JAR 后用真实 CCX 案例复跑"research + 写文件"，确认 task 不再卡 `waiting_human`、产物落地、`selectedWorker=codex`。
- 评估是否把 pinned 契约兜底推广到更多 taskType（当前与 `shouldApplyAutoRouteTaskTypeContract` 保持一致）。
