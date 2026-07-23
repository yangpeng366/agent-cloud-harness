# M02 Packet Schema Execution Record 2026-06-30

## 1. 用途

本文档记录 M02 `Packet schema 固化` 的一次协议复核、HTTP contract 补齐与 focused suite 验证结果。

## 2. 基本信息

- 日期：2026-06-30
- 执行人：Codex
- 任务编号：M02
- 任务标题：Packet schema 固化
- 任务类型：modify
- task_pack：project_evolution_v1
- task_case_key：M02
- task_family：modify
- task_length_bucket：medium
- model_mode：orchestrated
- acceptance_gate：contract_regression
- 相关文档：
  - `PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
  - `TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
  - `API_CONTRACTS.md`

## 3. 任务目标

```text
把 resume packet 与 handoff packet 的最小字段写成可依赖 contract，并让 API、测试、文档对齐。
```

## 4. 执行过程

### Round 1

- 发现：`API_CONTRACTS.md` 已经把 `ResumePacket` / `HandoffPacket` 的最小 typed schema 写明，且明确 `machine-readable first`；builder、service、checkpoint 路径需要先确认是否已经和文档一致。
- 使用的检查：
  - `PacketBuilderProtocolTest`
  - `TaskServicePacketContractTest`
  - `ConsolidationServiceProtocolTest`
  - `API_CONTRACTS.md`
  - `PacketBuilder.java`
  - `TaskService.java`
  - `ResumePacket.java`
  - `HandoffPacket.java`
- 结论：builder / service / consolidation 的核心协议已基本对齐，真实缺口更可能出现在 API 层缺少直接 contract 证据，而不是 runtime 结构缺字段。

### Round 2

- 发现：先跑 packet 相关 focused suite 后，builder / service / checkpoint 相关测试全部通过，说明最小 schema 没有明显实现偏差。
- 使用的测试 / probe：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=PacketBuilderProtocolTest,TaskServicePacketContractTest,ConsolidationServiceProtocolTest"
```

- 证据：命令退出码 `0`
- 结论：M02 不需要先改 runtime；下一步应补 `/packet` 与 `/refresh_packet` 的 HTTP typed schema 断言。

### Round 3

- 发现：`handoff_packet` 已有 HTTP contract 覆盖，但 `resume packet` 的 `/refresh_packet` 与 `/packet` 还缺直接接口级断言。
- 使用的检查：
  - `TaskHandlerControlActionHttpTest`
  - `TaskHandler` 的 packet / refresh_packet 路由
- 代码补充：
  - 在 `TaskHandlerControlActionHttpTest` 新增 `getAndRefreshResumePacketReturnTypedMachineReadableSchema`
  - 新增 `ResumePacketHarness`
- 新锁定的接口字段：
  - `packet_version=1.1`
  - `machine_readable_first=true`
  - `task_identity.task_id/session_id/task_type`
  - `current_objective`
  - `current_status`
  - `current_node`
  - `assigned_worker`
  - `latest_summary`
  - `next_step`
  - `blockers`
  - `open_questions`
  - `recent_artifacts`
  - `recent_decisions`
  - payload 中的 continuity alias
- 结论：当前 resume packet 已在 HTTP 层具备和 handoff packet 同等级别的 typed contract 保护。

### Round 4

- 发现：补完 HTTP contract 后，当前 packet focused suite 可一次性覆盖 builder、service、checkpoint 与 HTTP API 四层。
- 使用的测试 / probe：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=PacketBuilderProtocolTest,TaskServicePacketContractTest,ConsolidationServiceProtocolTest,TaskHandlerControlActionHttpTest"
```

- 证据：命令退出码 `0`
- 结论：M02 当前这轮目标已经从“文档有 schema”推进到“文档 + builder + service + checkpoint + HTTP contract”一致。

## 5. 测试与探针

### 5.1 focused tests

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=PacketBuilderProtocolTest,TaskServicePacketContractTest,ConsolidationServiceProtocolTest,TaskHandlerControlActionHttpTest"
```

结果：

```text
PASS
```

### 5.2 HTTP contract 覆盖点

- `TaskHandlerControlActionHttpTest#getAndRefreshResumePacketReturnTypedMachineReadableSchema`
- 既验证 `GET /api/v1/tasks/{id}/refresh_packet`
- 也验证 `GET /api/v1/tasks/{id}/packet`

## 6. 观测证据

- `packet_version`：`1.1`
- `machine_readable_first`：`true`
- `task_identity`：接口层直接返回结构化 object，而不是散装 map
- `recent_artifacts / recent_decisions`：接口层返回结构化列表
- `payload`：继续保留扩展包，但已镜像 continuity machine-readable 字段
- `handoff_packet`：本轮未新增实现，但其 typed schema 覆盖仍保留

## 7. 代码与文档变更

- 修改文件：
  - `src/test/java/com/agentcloud/server/TaskHandlerControlActionHttpTest.java`
  - `docs/PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
  - `docs/TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
  - `docs/continuity/README.md`
  - `docs/evaluation/README.md`
  - `STATE.md`
- 新增文件：
  - `docs/M02_PACKET_SCHEMA_EXECUTION_RECORD_2026-06-30.md`
- 关键改动摘要：
  - 把 `resume packet` 的 `/refresh_packet` 与 `/packet` HTTP typed schema 缺口补成 focused regression。
  - 把 M02 的 builder / service / checkpoint / HTTP API 四层证据写回多轮任务链与专题入口。

## 8. 验收结果

- `acceptance_result`：pass
- `failure_reason`：N/A
- `next_action`：继续沿 packet / continuity 主线推进 D04、O04 或后续 replay / recovery 相关任务时，优先复用这组 packet focused suite。
- `是否需要继续补 runtime 代码`：当前不需要；后续若 packet 最小字段扩张，应先更新 `API_CONTRACTS.md` 和 focused tests，再动实现。

## 9. 结论

```text
M02 当前已形成可依赖的 contract 证据链：API_CONTRACTS 定义了 packet 最小字段，builder / service / checkpoint 测试已绿，resume packet 的 /refresh_packet 与 /packet 也已在 HTTP 层锁定 typed schema。
```
