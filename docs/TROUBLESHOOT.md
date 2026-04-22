# Troubleshoot

## 1. Gotchas — 已知坑点

### G01: 暂停时构建了 resume packet，但没有持久化

- **位置**: `src/main/java/com/agentcloud/engine/ControlNodeGraph.java:95`
- **现象**: 任务暂停后能看到 checkpoint，但不一定能在 `resume_packets` 表中看到新的 packet。
- **原因**: `packetNode` 调用了 `packetBuilder.buildResumePacket(task, session)`，却没有把返回值写入 `ResumePacketDao`。
- **规避方式**: 如果依赖 `/api/v1/tasks/{id}/packet` 读取最新包，应先调用 `/refresh_packet`；后续建议在 `packetNode` 中显式保存 packet。
- **代码证据**: `packetNode` 只构建对象，没有 `packetDao.insert(...)`。

### G02: Consolidation 查询 artifact 时 sessionId 传错

- **位置**: `src/main/java/com/agentcloud/engine/ConsolidationService.java:36`
- **现象**: 某些任务明明已有 artifact，但生成的 checkpoint 中 `key_artifacts` 可能为空或不完整。
- **原因**: `artifactDao.listBySessionAndTask(task.id(), task.id(), 20)` 第一个参数应是 `sessionId`，当前误传了 `task.id()`。
- **规避方式**: 在排查 checkpoint 内容缺失时，优先核对该处参数；修复前不要把 checkpoint 视为完整事实源。
- **代码证据**: 同类查询 `decisionDao` 和 `eventDao` 都使用 `task.sessionId()`，唯独 artifact 查询不同。

### G03: 多个状态变更接口使用 GET

- **位置**: `src/main/java/com/agentcloud/server/TaskHandler.java:47`, `:50`, `:53`, `:56`; `src/main/java/com/agentcloud/server/SessionHandler.java:42`
- **现象**: 代理、浏览器预取、缓存层或某些 API 网关可能把这些请求当成幂等读操作处理，造成意外状态变更。
- **原因**: `pause/resume/continue/escalate/close` 都暴露成 GET，而非 POST/PATCH。
- **规避方式**: 外部接入时禁用预取和缓存；正式化接口前建议改成 POST 或 PATCH。
- **代码证据**: handler 直接在 GET 分支里执行写操作。

### G04: Query 过滤键名和服务层参数名不一致

- **位置**: `src/main/java/com/agentcloud/server/TaskHandler.java:37`
- **现象**: 使用 `?status=active` 可能得不到预期结果，必须传 `?state=active`。
- **原因**: `listTasks` 方法入参名是 `status`，但 handler 从 query 中读取的是 `state`。
- **规避方式**: 调用方使用当前实现要求的 `state`；如果要对外公开，建议统一命名。
- **代码证据**: `params.get("state")` 被传入 `svc.listTasks(...)` 的第一个参数。

## 2. 常见错误场景

### 2.1 服务启动失败，提示无法打开数据库

- **典型表现**: 启动阶段抛出 SQLite 或文件权限相关异常。
- **可能原因**:
  1. 用户目录不可写 — 检查 `src/main/java/com/agentcloud/cli/Main.java`
  2. `schema.sql` 未打入资源包 — 检查 `src/main/resources/schema.sql`
- **排查步骤**:
  1. 确认 `${user.home}\\.agentcloud\\` 目录存在且可写。
  2. 检查打包后的 JAR 是否包含 `schema.sql`。
  3. 查看控制台或 `server.err.log` 输出。
- **解决方案**: 修正用户目录权限，或通过运行用户环境确保能创建 DB 文件。

### 2.2 任务已暂停但 `/packet` 为空

- **典型表现**: `/api/v1/tasks/{id}/pause` 返回成功，随后 `/api/v1/tasks/{id}/packet` 得到 `null`。
- **可能原因**:
  1. 暂停路径没有持久化 resume packet — 检查 `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
  2. 任务尚未显式执行 `/refresh_packet` — 检查调用链
- **排查步骤**:
  1. 查询 `checkpoints` 表确认是否已生成 checkpoint。
  2. 手工调用 `/api/v1/tasks/{id}/refresh_packet`。
  3. 再次请求 `/api/v1/tasks/{id}/packet`。
- **解决方案**: 作为临时方案先调用 `/refresh_packet`；根治方案是修复 `packetNode` 写库逻辑。

### 2.3 Worker readiness 看似正常，但路由结果不符合预期

- **典型表现**: 任务分配给 fallback worker，或没有按能力最优匹配。
- **可能原因**:
  1. 任务 `metadata.task_type` 缺失，路由回落到 `general` — 检查创建任务请求
  2. worker 虽 `ready=true`，但 capability 不包含目标任务类型 — 检查 `WorkerRegistry`
- **排查步骤**:
  1. 查看任务 `metadata_json` 中的 `task_type`。
  2. 调用 `/api/v1/workers` 和 `/api/v1/workers/{id}/readiness`。
  3. 对照 `WorkerRouter.selectWorker` 的筛选规则。
- **解决方案**: 创建任务时明确传 `task_type`，并为 worker 正确注册 capability。

## 3. 调试技巧

### 3.1 本地调试

- **日志位置**: 默认输出到控制台；当前工作区有 `server.out.log` 样本。
- **日志级别配置**: `src/main/resources/logback.xml` 中 root 为 `INFO`。
- **调试工具**: 直接用 IDE 附加断点即可，项目无额外容器依赖。

### 3.2 常用调试命令

```bash
# 编译并打包
mvn package

# 启动服务
java --enable-preview -jar target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar

# 创建一个 coding 任务
curl -X POST http://localhost:8080/api/v1/tasks -H "Content-Type: application/json" -d "{\"title\":\"demo\",\"task_type\":\"coding\",\"source\":\"user\",\"priority\":\"high\",\"intent\":\"fix bug\"}"

# 查看 worker 列表
curl http://localhost:8080/api/v1/workers
```

### 3.3 关键断点位置

| 场景 | 建议断点位置 | 说明 |
|------|------------|------|
| 创建任务后为何分配到某个 worker | `src/main/java/com/agentcloud/engine/router/WorkerRouter.java:18` | 可以看到 taskType 推导和 fallback 逻辑 |
| 暂停任务后为何没有 packet | `src/main/java/com/agentcloud/engine/ControlNodeGraph.java:91` | 能确认 packet 只构建未落库 |
| checkpoint 内容为何缺 artifact | `src/main/java/com/agentcloud/engine/ConsolidationService.java:36` | 能直接看到错误参数 |
| 路由注册是否成功 | `src/main/java/com/agentcloud/server/NioHttpServer.java:46` | 确认上下文已挂载 |

## 4. 配置相关注意事项

### 4.1 容易出错的配置

| 配置项 | 位置 | 常见错误 | 正确做法 |
|--------|------|---------|---------|
| `server.port` | JVM System Property | 忘记传导致端口冲突排查方向错误 | 明确在启动参数中覆盖 |
| `user.home` | 运行环境 | 使用受限账户导致无法创建 `.agentcloud` | 确保运行用户有写权限 |
| `schema.sql` | `src/main/resources/schema.sql` | 打包缺失导致启动失败 | 保持资源文件在主资源目录 |

### 4.2 环境差异

| 差异点 | 开发环境 | 测试环境 | 生产环境 |
|--------|---------|---------|---------|
| 数据库存储 | 本地用户目录 SQLite | 未发现独立配置 | 未发现独立配置 |
| Worker 注册 | 进程内预注册 + API 动态注册 | 同开发 | 同开发 |
| 日志输出 | 控制台 `INFO` | 取决于启动脚本 | 取决于进程托管方式 |

## 5. 性能隐患

| 编号 | 位置 | 问题描述 | 风险等级 | 建议 |
|------|------|---------|---------|------|
| P01 | `src/main/java/com/agentcloud/server/TaskHandler.java` | 列表接口固定只取最近 100 条，缺少真正分页能力 | 中 | 增加分页参数和总量查询 |
| P02 | `src/main/java/com/agentcloud/store/DatabaseManager.java:62` | 启动时按分号手工切分 schema 执行，schema 变复杂后容易出错 | 低 | 引入更稳健的 migration 机制 |
| P03 | `src/main/java/com/agentcloud/engine/ConsolidationService.java` | consolidation 每次都查多张表并在 API 线程内同步执行 | 中 | 后续可异步化或加限流 |

## 6. 安全注意事项

| 编号 | 位置 | 问题描述 | 风险等级 | 建议 |
|------|------|---------|---------|------|
| S01 | `src/main/java/com/agentcloud/server` | 全部 API 无鉴权、无租户隔离 | 高 | 增加认证和最小权限控制 |
| S02 | `src/main/java/com/agentcloud/server/WorkerHandler.java:39`, `SkillHandler.java:31` | 直接接受外部请求注册 worker/skill，缺少校验 | 中 | 增加字段校验与权限限制 |
| S03 | `src/main/java/com/agentcloud/server/*Handler.java` | 500 响应直接回传 `e.getMessage()`，可能泄露内部细节 | 中 | 对外返回通用错误码，详细异常只写日志 |

## 7. 运维检查清单

- [ ] 启动用户对 `${user.home}/.agentcloud/` 目录具备写权限。
- [ ] 监听端口 `8080` 或自定义 `server.port` 未被占用。
- [ ] 启动日志中已出现 `NIO HTTP Server started` 和 endpoint 列表。
- [ ] `/api/v1/health` 返回 `status=up`。
- [ ] `skills`、`workers` 基础数据符合当前运行环境预期。
- [ ] 若依赖暂停恢复流程，先验证 `checkpoints` 与 `resume_packets` 是否都按预期写入。

## 8. TODO/FIXME 汇总

本项目未发现相关内容，原因是源码中没有搜到 `TODO`、`FIXME`、`HACK`、`WORKAROUND`、`BUG`、`DEPRECATED` 等标记。
