# Dialogue / Console / Facade

本专题覆盖 `/dialogue/`、`/console/`、chat facade、UI 展示、operator 诊断面，以及相关 runbook / acceptance record。

当前 `dialogue/` 已升级到轻量工作区：除 `README.md` 外，已启用 `PROGRESS.md`，并新增 `runs/README.md` 作为 dated acceptance / execution / precheck 聚合入口，用来承接 `/dialogue/`、`/console/`、acceptance、operator 读面的持续推进。当前默认阅读顺序是 `README.md -> PROGRESS.md -> 当前子线文档 -> runs/README.md`；`tasks/`、`archive/` 仍未启用。

当前 dialogue 主题内部也已经不止一条线，不要把所有 `DIALOGUE_*` 文档都当成并列主线。先判断当前任务属于哪一类，再进入对应子主题：

- chat-first 产品壳层 / 默认交互
- message / façade / continuity contract
- 浏览器验证 / acceptance / release gate
- console / operator 诊断面
- 布局与渲染实验

## 命中信号

- 任务提到 `/dialogue/`、`/console/`、SSE、chat facade、消息流
- 任务提到 UI 布局、pinned 输出、diagnostics、operator 诊断层
- 任务提到浏览器验证、acceptance、截图、probe

## 先做子主题判断

| 当前问题 | 先看哪里 | 再下钻 |
|------|------|------|
| 今天 `/dialogue/` 和 `/console/` 默认该长什么样、用户第一屏应看到什么 | `../WEB_CONSOLE.md` | `../CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`、`../DIALOGUE_CODEX_UI_ADAPTATION_PLAN.md` |
| 要改 transcript / composer / task thread / pinned output / chat-first 壳层 | `../DIALOGUE_CODEX_UI_ADAPTATION_PLAN.md` | `../DIALOGUE_PRESENTATION_IMPROVEMENT_PLAN.md`、`../DIALOGUE_UI_OPTIMIZATION_PLAN.md` |
| 要改 message/task 关系、chat façade、`/v1/chat/completions` / `/v1/responses` 行为 | `../DIALOGUE_MESSAGE_LAYER_PLAN.md` | `../CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`、`../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md` |
| 要跑页面验证、browser acceptance、SSE 或 façade continuity | `../DIALOGUE_UI_VALIDATION_RUNBOOK.md` | `../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`、`../DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md` |
| 要看 `/console/`、provider 读面、`provider_run_file` SSE、worker round/operator 诊断 | `../WEB_CONSOLE.md` | `../AGENT_INVENTORY_AND_RUNTIME_HEALTH_CONSOLE_PLAN.md`、`../LIVE_FLOW_RUNBOOK.md`、`../HARNESS_REDESIGN_FOR_LOCAL_AGENTS.md` |
| 要做页面 release gate / GitHub 上架前测试矩阵 | `../DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md` | `../DIALOGUE_GITHUB_RELEASE_PRECHECK_2026-05-12.md` |
| 要回看某一轮 acceptance / execution / precheck 证据 | `runs/README.md` | 再进入对应 dated record，并把仍然有效的结论回收到 runbook / `WEB_CONSOLE.md` / `PROGRESS.md` |

## 最小阅读顺序

1. `PROGRESS.md`
2. `../WEB_CONSOLE.md`
3. `../DIALOGUE_UI_VALIDATION_RUNBOOK.md`
4. `../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
5. `../TROUBLESHOOT.md`
6. 如果任务已经明确是在查 dated acceptance / execution / precheck 证据，转到 `runs/README.md`。
7. 其余情况再按上面的子主题判断进入对应文档，不需要把所有 `DIALOGUE_*` 文档全文扫一遍。

## 稳定基线

- `../WEB_CONSOLE.md`
- `../DIALOGUE_UI_VALIDATION_RUNBOOK.md`
- `../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `../TROUBLESHOOT.md`

这些文档更接近“今天仍然为真”的页面行为、验证入口和排障口径。若本轮改动改变了默认交互、验证链或 operator 读面，优先回写这里。

## 当前主线文档

### 主题进度

- `PROGRESS.md`

### Chat-first 产品壳层

- `../CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`
- `../DIALOGUE_CODEX_UI_ADAPTATION_PLAN.md`
- `../DIALOGUE_PRESENTATION_IMPROVEMENT_PLAN.md`
- `../DIALOGUE_UI_OPTIMIZATION_PLAN.md`
- `../LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md` — 下一阶段方向主入口：UI 页面展示结果 / 返回与执行中状态判断（active / running / waiting_human / failed / partial / done）

### Message / Facade / Continuity

- `../DIALOGUE_MESSAGE_LAYER_PLAN.md`

### 布局与渲染实验

- `../DIALOGUE_DYNAMIC_RENDER_V2.md`
- `../DIALOGUE_ELASTIC_CARD_PLAN.md`
- `../DIALOGUE_PRETEXT_DYNAMIC_LAYOUT_PLAN.md`

### 验证与 release gate

- `../DIALOGUE_UI_VALIDATION_RUNBOOK.md`
- `../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `../DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md`

### Console / Operator / 诊断读面

- `../WEB_CONSOLE.md`
- `../AGENT_INVENTORY_AND_RUNTIME_HEALTH_CONSOLE_PLAN.md`
- `../LIVE_FLOW_RUNBOOK.md`
- `../HARNESS_REDESIGN_FOR_LOCAL_AGENTS.md`

### Dated Evidence 聚合入口

- `runs/README.md`

## 验收与证据

- `../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md`
- `../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md`
- `../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`
- `../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-14.md`
- `../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-06-02.md`
- `../DIALOGUE_GITHUB_RELEASE_PRECHECK_2026-05-12.md`
- `../D03_CHAT_FACADE_EXECUTION_RECORD_2026-06-15.md`

## 写回顺序

- 主题级短进展、当前焦点、未完成/下一步/风险：
  - 优先写 `PROGRESS.md`

- transcript / composer / task thread / pinned output / chat-first 壳层变化：
  - 优先写 `CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`
  - 或最贴近的 `DIALOGUE_*` 产品/UI 计划文档
- message 层、façade contract、continuity 路径变化：
  - 优先写 `DIALOGUE_MESSAGE_LAYER_PLAN.md`
  - 验证链变化同步 `DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- 页面验证、browser acceptance、release gate 变化：
  - 优先续写 `DIALOGUE_UI_VALIDATION_RUNBOOK.md`、`DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`、`DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md`
- console / operator / provider_run_file / worker round 读面变化：
  - 优先写 `WEB_CONSOLE.md`
  - 需要时补 `AGENT_INVENTORY_AND_RUNTIME_HEALTH_CONSOLE_PLAN.md`、`LIVE_FLOW_RUNBOOK.md`、`HARNESS_REDESIGN_FOR_LOCAL_AGENTS.md`
- 需要保留真实浏览器或接口证据：
  - 写 dated acceptance/execution record / precheck
  - 同步把入口补进 `runs/README.md`
  - 再把跨主题摘要写入 `../STATE.md`

## 历史材料使用规则

- 旧 acceptance record 和 precheck 只用来对比回归，不应用来替代当前 runbook 或 release gate 入口。
- 需要在多份 acceptance / precheck / execution evidence 之间切换时，先从 `runs/README.md` 进入，不要在 root-level 长名单里猜。
- `DIALOGUE_DYNAMIC_RENDER_V2.md`、`DIALOGUE_ELASTIC_CARD_PLAN.md`、`DIALOGUE_PRETEXT_DYNAMIC_LAYOUT_PLAN.md` 更适合在做布局/渲染优化时再进入，不应作为所有 dialogue 任务的第一入口。
- `HARNESS_REDESIGN_FOR_LOCAL_AGENTS.md` 是跨主题的读面/执行链诊断文档；只有任务明确落在 worker round、provider output、operator 读面时才优先进入。
- 如果某个旧验收结论仍然是今天的默认行为，应提炼回 `WEB_CONSOLE.md`、当前 runbook 或活跃计划文档。

## 当前入口建议

- 要先看最近活跃焦点和风险：`PROGRESS.md`
- 要理解 UI 行为和观测面：`../WEB_CONSOLE.md`
- 要改 chat-first 壳层：`../CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md`、`../DIALOGUE_CODEX_UI_ADAPTATION_PLAN.md`
- 要改 message / façade / SSE contract：`../DIALOGUE_MESSAGE_LAYER_PLAN.md`
- 要执行实际验证：`../DIALOGUE_UI_VALIDATION_RUNBOOK.md`
- 要核对 chat facade / SSE acceptance：`../DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- 要做页面 release gate：`../DIALOGUE_GITHUB_RELEASE_TEST_MATRIX.md`
- 要核对 `provider_run_file` SSE、worker round 排障面和 operator 读面边界：`../HARNESS_REDESIGN_FOR_LOCAL_AGENTS.md`
- 要回看 acceptance / precheck / execution 证据：`runs/README.md`
