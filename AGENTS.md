# Agent Cloud Harness — Agent 指南

> 本文档只回答 Agent 开工时必须先知道的事：先读什么、哪些规则不能破、项目事实去哪里查、做完写回哪里。

## 每次开工

开始任何实质工作前，默认按下面顺序建立上下文；这些读取不需要额外询问用户。

1. 读 `WAKE.md`。
2. 回读本文件，先看 `连续性优先`、`文档目录边界`、`开工红线`、`已知陷阱`。
3. 读 `docs/README.md`，先用“按任务找入口”判断当前任务属于哪个主题。
4. 读 `STATE.md` 和 `DECISIONS.md`，确认最近进度与已收敛取舍。
5. 按任务主题先读对应专题入口 `docs/<topic>/README.md`；如果该主题已启用 `PROGRESS.md`，接着读 `PROGRESS.md`，再补读最贴近的具体文档；如果主题仍不清楚，回到 `docs/README.md` 的任务分流表重新判断。
6. 如果任务偏对外说明、启动方式或安装排障，再补读 `README.md` 和 `STARTUP_GUIDE.md`。
7. 如果需要项目事实，不要先在本文件里翻长说明，直接去对应稳定文档：
   - 架构/模块边界：`docs/ARCHITECTURE.md`
   - API/存储契约：`docs/API_CONTRACTS.md`
   - 功能规格/状态机：`docs/SPEC.md`
   - 已知坑点/排障：`docs/TROUBLESHOOT.md`
   - Web Console / Dialogue 读面：`docs/WEB_CONSOLE.md`

## 连续性优先

- 有实质进展时，至少在 `docs/*.md`、`STATE.md`、`DECISIONS.md` 之一留下写回痕迹。
- 任务方向变化时，优先更新当前主题文档；没有合适落点时再补到 `STATE.md`。
- 工作中断时，要把“已完成 / 未完成 / 下一步 / 风险”写回，而不是只停留在对话里。
- 文档整理类任务先改索引和入口，再考虑是否需要迁移历史文件。
- 不要引入 `articleeditor` 那种额外 `memory/`、`state/` 目录树；本仓库继续使用轻量写回面。

## 文档目录边界

- 根目录 `README.md` / `STARTUP_GUIDE.md`：对外介绍、构建启动、安装排障。
- 根目录 `WAKE.md` / `AGENTS.md`：Agent 开工入口、协作约束、写回规则。
- 根目录 `STATE.md` / `DECISIONS.md`：跨主题进度摘要、稳定设计决策。
- `docs/README.md`：`docs/` 总索引、分类入口、新文档落点约定。
- `docs/DOCS_GOVERNANCE.md`：文档结构合同、命名合同、审计入口、工作区升级规则。
- `docs/meta/README.md`：文档治理专题入口；处理结构优化、索引审计、入口收口、规则同步。
- `docs/<topic>/README.md`：主题入口、阅读顺序、写回地图；先用它决定该读哪几份 plan/runbook/record。
- `docs/<topic>/PROGRESS.md`（可选）：主题级活跃进度摘要；只在该主题持续高频推进时启用。
- `docs/<topic>/tasks/`（可选）：主题内并行子任务、拆分计划、专项记录。
- `docs/<topic>/runs/`（可选）：主题级 dated execution/acceptance/precheck 证据聚合面。
- `docs/<topic>/archive/`（可选）：已降级历史材料；不作为默认入口。
- `.tmp/`：临时探针、截图、脚本输出、草稿。若后续要持续引用，必须提炼迁入 `docs/`。

## 当前工作区现状

- `meta/`: `README.md + PROGRESS.md`
- `continuity/`: `README.md + PROGRESS.md`
- `provider/`: `README.md + PROGRESS.md`
- `dialogue/`: `README.md + PROGRESS.md`
- `evaluation/`: `README.md + PROGRESS.md`
- `release/`: `README-only`
- 已启用 `PROGRESS.md` 的主题：`README.md -> PROGRESS.md -> 当前主线文档`
- `README-only` 主题：`README.md -> docs/` 根目录主线文档

## 开工红线

- 文档结构优化任务先从 `docs/meta/README.md` 进入，不要直接在 root-level 长名单里改一圈。
- 新增的 `plan / runbook / execution record / acceptance record / precheck` 必须能从某个专题入口追到；不要让正式文档只留在 `docs/` 根目录裸放。
- 历史文档优先“提炼吸收”，不要把物理迁移当第一步。
- 需要稳定项目事实时，优先查正式基线文档；不要把本文件重新扩回“项目百科”。

## 项目事实入口

### 稳定事实

- `docs/ARCHITECTURE.md`
- `docs/API_CONTRACTS.md`
- `docs/SPEC.md`
- `docs/TROUBLESHOOT.md`
- `docs/WEB_CONSOLE.md`

### 当前推进主线

- `docs/continuity/README.md`
- `docs/provider/README.md`
- `docs/dialogue/README.md`
- `docs/evaluation/README.md`
- `docs/release/README.md`
- `docs/meta/README.md`

### 启动与公开入口

- `README.md`
- `STARTUP_GUIDE.md`

## 已知陷阱（修改前必读）

以下条目按当前源码状态区分为“已收口的历史回归点”和“仍然存在的真实风险”：

### T01: pause 持久化缺口已收口

- **当前状态**：`pause -> packet` 路径现在会持久化最新 `ResumePacket`，暂停后可直接通过 `/api/v1/tasks/{id}/packet` 取回。
- **回归保护**：`TaskServicePacketContractTest.pauseTaskPersistsResumePacketAndPauseCheckpoint()`
- **修改时注意**：不要只构建 packet 不落库；暂停链路还要同时保留 `pause_before` checkpoint。

### T02: Consolidation artifact 查询参数已收口

- **当前状态**：`ConsolidationService` 已按 `task.sessionId()` + `task.id()` 查询 artifact，`key_artifacts` 会进入 checkpoint/refined packet。
- **回归保护**：`ConsolidationServiceProtocolTest.consolidateProducesCheckpointProtocolPayload()`
- **修改时注意**：任何 checkpoint/refined packet 相关重构，都不要把 sessionId/taskId 顺序再改坏。

### T03: 列表查询参数兼容已收口

- **当前状态**：`GET /api/v1/tasks` 现已同时接受 `status` 和旧参数 `state`。
- **回归保护**：`TaskHandlerControlActionHttpTest.listTasksAcceptsStatusAndLegacyStateQueryParams()`
- **修改时注意**：新代码统一以 `status` 为主，但不要轻易移除 `state` 兼容，除非同步做 API 版本升级。

### T04: 错误响应脱敏已收口

- **当前状态**：Handler 统一通过 `NioHttpServer` 返回稳定错误体；`500` 固定为 `internal error`，不再直接回传内部异常细节。
- **回归保护**：`ControlActionHttpRouteTest.postPauseHidesInternalFailureDetails()`、`ApiErrorContractHttpTest`
- **修改时注意**：日志里可以保留异常详情，但 HTTP 响应层不要重新暴露 `e.getMessage()`。

### T05: 仍然存在的真实风险

- **位置**：所有 Handler
- **现状**：API 仍然是匿名访问，尚无认证、授权、租户隔离和限流。`WorkerHandler` / `SkillHandler` 已补了基础字段校验与部分类型校验，但这不等于安全边界。
- **影响**：任何能访问 HTTP 端口的调用方都能读写控制面数据，仍然只适合本地或受控环境。

## 代码与改动约束

- **文件编码**：代码文件统一使用 UTF-8 无 BOM。
- **语言**：代码标识符用英文，注释以中文为主。
- **不可变更新**：领域对象状态变更一律使用 `withXxx()` 返回新 record，不要直接修改字段。
- **日志**：统一使用 SLF4J。
- **异常**：服务层常用 `IllegalArgumentException` / `IllegalStateException`；Handler 会把可识别问题映射成 `400/404`，其他未处理异常统一脱敏成 `500 internal error`。
- **新增 HTTP 资源**：沿用现有 `XxxHandler implements HttpHandler` 模式。
- **新增数据实体**：在 `model/` 定义 Record，在 `store/` 定义 DAO，在 `schema.sql` 加表，在 `DatabaseManager` 注册 RowMapper。
- **不要引入**：Spring Boot、额外 Web 框架、全局 memory/state 目录树。

## 调研与文档沉淀约定

- 对于调研、排查、方案设计、验收结论整理这类任务，默认先把结论沉淀到仓库文档，再决定是否动代码。
- 文档优先落到最贴近主题的现有 `docs/*.md`；只有现有文档都不合适时，才新增新的 plan / runbook / record。
- 如果是文档结构优化，顺序固定为：先改 `docs/README.md`，再改对应 `docs/<topic>/README.md`，如果规则本身变化再同步 `docs/DOCS_GOVERNANCE.md`，最后才考虑是否需要调整历史文件或根入口说明。
- 如果某个主题已经启用了 `PROGRESS.md / tasks/ / runs / archive`，默认优先续写该主题工作区，而不是在 `docs/` 根目录再平铺一份近义文档。
- 如果调研最终导向代码修改，顺序应优先是：先更新文档，再修改代码，再补验证证据。
- 不要把关键调研结论只留在对话里；至少应在文档中写清：背景/问题、真实结论、证据或验证入口、后续动作。

## 安全注意事项

| 编号 | 风险 | 等级 | 说明 |
|------|------|------|------|
| S01 | 无认证/授权 | 高 | 所有端点匿名可访问 |
| S02 | 无输入校验 | 中 | Worker/Skill 注册直接写内存 |
| S03 | 信息泄露 | 低 | ~~500 错误直接返回异常消息~~ 已收口：500 统一返回 `internal error`，异常详情仅写日志 |
| S04 | 无租户隔离 | 高 | 所有数据共享同一个 SQLite 文件 |

## 写回顺序

- 当前主题的入口、方案、runbook、record 优先写回对应专题文档。
- 结构治理或入口规则变更，写回 `docs/meta/PROGRESS.md` 与 `docs/DOCS_GOVERNANCE.md`。
- 跨主题短摘要写 `STATE.md`。
- 稳定规则写 `DECISIONS.md`。
