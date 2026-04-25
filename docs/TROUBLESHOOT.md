# Troubleshoot

## 1. Gotchas — 已知坑点

### G01: 多个状态变更接口使用 GET

- **位置**: `src/main/java/com/agentcloud/server/TaskHandler.java:47`, `:50`, `:53`, `:56`; `src/main/java/com/agentcloud/server/SessionHandler.java:42`
- **现象**: 代理、浏览器预取、缓存层或某些 API 网关可能把这些请求当成幂等读操作处理，造成意外状态变更。
- **原因**: `pause/resume/continue/escalate/close` 都暴露成 GET，而非 POST/PATCH。
- **规避方式**: 外部接入时禁用预取和缓存；正式化接口前建议改成 POST 或 PATCH。
- **代码证据**: handler 直接在 GET 分支里执行写操作。

### G02: 任务列表过滤键名存在双写兼容期

- **位置**: `src/main/java/com/agentcloud/server/TaskHandler.java:37`
- **现象**: 当前同时兼容 `?state=active` 和 `?status=active`，对外契约若不收口，SDK 可能继续分叉。
- **原因**: 为兼容旧调用方，handler 同时接受 `state/status` 两个查询键。
- **规避方式**: 对外文档优先统一成 `state`，内部过渡期保留双写兼容。
- **代码证据**: handler 先取 `status`，为空时再回退到 `state`。

### G03: tool-aware 执行器当前只支持单工具单轮

- **位置**: `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java`
- **现象**: 某些需要“先检索再读取再写回”的复杂任务，第一轮只能完成一次工具调用，结果质量可能有限。
- **原因**: 当前实现是最小双阶段协议，只支持 `planning -> invoke one tool -> finalization`。
- **规避方式**: 先用它验证“受控工具能力 + 可观测性”已打通；不要把它当成多轮 thread bridge。
- **代码证据**: executor 只解析一次 `ToolPlan`，并在一次工具调用后直接收敛最终 `WorkerExecutionResult`。

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

### 2.2 关键迁移后 packet 不符合预期

- **典型表现**: `/pause`、`/escalate` 或 `/handoff` 返回成功，但 `resume_packets` / `checkpoints` 中固化内容和期望不一致。
- **可能原因**:
  1. 任务本身缺少足够的 `decision/artifact/event` 轨迹，packet 只能生成空摘要。
  2. 调用的是 `handoff_packet` 预览接口，而不是正式的 `/handoff` 执行接口。
- **排查步骤**:
  1. 先查 `resume_packets` 是否新增记录。
  2. 再查 `checkpoints` 表中的 `checkpoint_type` 是否为 `pause_before`、`escalate_before`、`handoff_before` 或 `halt_before`。
  3. 对照 `events/decisions/artifacts` 是否已有足够输入轨迹。
- **解决方案**: 先补轨迹数据，再看 packet；如果只是预览交接内容，使用 `/handoff_packet`，不要把它当作执行型接口。

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

### 2.4 `/tool_trace` 为空，或 `live_flow` 中没有工具轨迹

- **典型表现**: 任务执行过后，`/api/v1/tasks/{id}/tool_trace` 返回空数组，或 `/api/v1/tasks/{id}/live_flow` 中 `tool_invocations` 为空。
- **可能原因**:
  1. 任务没有命中带工具能力的 worker，而是走了普通 `DefaultWorkerExecutor`
  2. worker 注册时 `suggest_only=true` 或 `tool_capabilities=[]`
  3. tool planning 判定 `needs_tool=false`
  4. planning/finalization 的 JSON 输出不符合协议，executor 回退到默认路径
- **排查步骤**:
  1. 先查 `/api/v1/tasks/{id}`，确认 `assigned_worker`
  2. 再查 `/api/v1/workers`，确认该 worker 的 `tool_capabilities`、`tool_scope`、`suggest_only`
  3. 再查 `/api/v1/tasks/{id}/live_flow?limit=10`，确认是否已有 judgment 但没有 `tool_invocations`
  4. 查看服务日志中的 tool planning / tool invocation 相关输出
- **解决方案**: 优先确认任务是否路由到了真正的 tool-aware worker；若只是试点验证，建议直接按 `docs/LOCAL_DOC_WORKER_PILOT.md` 使用自定义 `task_type=local_doc`。

### 2.5 本地文档试点任务没有命中 `kimi-local-doc`

- **典型表现**: 明明注册了 `kimi-local-doc`，但任务仍然分给了内置 `doc` worker。
- **可能原因**:
  1. 任务使用的是 `task_type=doc`，而不是试点专用的 `task_type=local_doc`
  2. `kimi-local-doc` 没带 `local_doc` capability
  3. worker 注册时 `ready=false`
- **排查步骤**:
  1. 查看任务创建请求中的 `task_type`
  2. 查看 `/api/v1/workers` 中 `kimi-local-doc` 的 capability 列表
  3. 对照 `docs/LOCAL_DOC_WORKER_PILOT.md` 的脚本参数和示例负载
- **解决方案**: 试点阶段优先使用 `local_doc`，避免和内置 `doc` capability 发生路由竞争。

### 2.6 `mvn package` 直接报“无效的目标发行版: 21”

- **典型表现**: Maven 在编译阶段直接失败，报错类似 `invalid target release: 21` 或 `无效的目标发行版: 21`。
- **可能原因**:
  1. `JAVA_HOME` 指向 Java 8 或其他低版本 JDK
  2. `mvn -v` 显示 Maven 正运行在非 Java 21 环境
- **本机已确认可用的 Java 21 安装**:
  - JDK 目录: `C:\Program Files\Java\jdk-21.0.9+10`
  - 版本标识: `jdk-21.0.9+10`
- **排查步骤**:
  1. 先执行 `mvn -v`，确认 Maven runtime Java 版本
  2. 再执行 `java -version`，确认命令行默认 JDK
  3. 对照 `pom.xml` 中的 `maven-compiler-plugin` 配置
- **解决方案**: 切换到 Java 21 后再运行构建；本项目启用了 preview 特性，低版本 JDK 无法通过编译。

Windows PowerShell 可直接临时切换：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.9+10"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -v
```

仓库内也已提供可直接使用的脚本：

```powershell
.\scripts\Build-WithJava21.ps1 -SkipTests
.\scripts\Run-HarnessWithJava21.ps1 -Port 18080 -Background
```

### 2.7 新打出来的 shaded JAR 能启动，但 `/health` 或其他 JSON 接口直接报 `BufferRecyclers`

- **典型表现**:
  1. 服务启动日志正常，端口也能监听
  2. 但首次访问 `/api/v1/health`、`/api/v1/tasks/...` 这类 JSON 接口时直接失败
  3. 日志里出现 `java.lang.NoClassDefFoundError: com/fasterxml/jackson/core/util/BufferRecyclers`
- **本机已确认的根因**:
  1. 进程环境里残留了全局 `CLASSPATH`
  2. 其值指向旧的 Java 8 运行库，例如：
     - `C:\Program Files\Java\jdk1.8.0_333\lib\dt.jar`
     - `C:\Program Files\Java\jdk1.8.0_333\lib\tools.jar`
- **排查步骤**:
  1. 先执行 `Get-ChildItem Env:CLASSPATH`
  2. 如果存在旧 JDK 路径，先切到项目脚本：`. .\scripts\Use-Java21.ps1`
  3. 再重新启动服务并访问 `/api/v1/health`
- **解决方案**:
  1. `Use-Java21.ps1` 现在会自动清空继承下来的 `CLASSPATH`
  2. 旧值会暂存到 `$env:AGENTCLOUD_PREVIOUS_CLASSPATH`
  3. 再用仓库脚本构建或启动即可

```powershell
. .\scripts\Use-Java21.ps1
.\scripts\Build-WithJava21.ps1 -SkipTests
.\scripts\Run-HarnessWithJava21.ps1 -Port 18080 -Background
```

补充说明：

- 已在本机确认：清空遗留 `CLASSPATH` 后，同一个 shaded JAR 可以正常返回 `/api/v1/health`。

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

本地文档试点也可以直接用脚本：

```powershell
.\scripts\Start-LocalDocPilot.ps1 `
  -BaseUrl "http://localhost:18080" `
  -ScopePath "D:\BaiduSyncdisk\Obsidian Vault\当前项目\02_项目推进\agent-cloud-architecture" `
  -OutputFileName "pilot-summary.md"
```

### 3.3 关键断点位置

| 场景 | 建议断点位置 | 说明 |
|------|------------|------|
| 创建任务后为何分配到某个 worker | `src/main/java/com/agentcloud/engine/router/WorkerRouter.java:18` | 可以看到 taskType 推导和 fallback 逻辑 |
| 关键迁移前是否真的固化了 packet | `src/main/java/com/agentcloud/engine/ControlNodeGraph.java` 中 `persistTransitionPacket` | 可同时确认 packet 和 checkpoint 是否写入 |
| checkpoint 内容为何缺 artifact | `src/main/java/com/agentcloud/engine/ConsolidationService.java:36` | 现在应能看到正确的 `sessionId` 参数 |
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
- [ ] 若启用了工具能力，确认 `/api/v1/tasks/{id}/tool_trace` 与 `/api/v1/tasks/{id}/live_flow` 中都能看到 `tool_invocations`。

## 8. TODO/FIXME 汇总

本项目未发现相关内容，原因是源码中没有搜到 `TODO`、`FIXME`、`HACK`、`WORKAROUND`、`BUG`、`DEPRECATED` 等标记。
