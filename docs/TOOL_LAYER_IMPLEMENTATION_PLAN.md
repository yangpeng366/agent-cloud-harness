# Tool Layer Implementation Plan

## 1. 目的

本文档承接前面关于 `Worker` 工具能力声明、`tool_invocations` 持久化、最小文件工具集的设计，继续把后续第 4-6 步收成可直接落地的实现说明。

目标不是一次把 `agent-cloud-harness` 做成通用 agent tool framework，而是先把下面这条链打通：

```text
worker contract
  -> tool policy
  -> tool invocation trace
  -> executor routing
  -> tool-aware worker round
  -> live flow observability
```

本文档聚焦：

1. `tool trace API` 如何接入当前 HTTP 服务
2. `WorkerExecutorRouter` 如何在不破坏 `ControlNodeGraph` 的前提下引入多执行器
3. `ToolAwareWorkerExecutor` 如何用最小双阶段协议跑通一次工具调用

---

## 2. 当前代码基线

在继续改造前，先明确当前几个关键落点：

- `src/main/java/com/agentcloud/worker/WorkerExecutor.java`
  - 当前 `ControlNodeGraph` 只依赖一个统一接口：`executeOneRound(TaskRuntimeContext context, String workerId)`
- `src/main/java/com/agentcloud/worker/DefaultWorkerExecutor.java`
  - 当前唯一执行器，负责组装 prompt，调用 LLM，解析 `WorkerExecutionResult`
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
  - scheduler node 中直接调用 `workerExecutor.executeOneRound(...)`
  - 这意味着后续如果要引入多种执行器，最自然的做法不是修改控制图逻辑，而是保持一个统一 `WorkerExecutor` 门面
- `src/main/java/com/agentcloud/engine/TaskService.java`
  - 已有 `getLiveFlow()` 聚合能力
  - 目前聚合内容包括 task / packet / route preview / runtime context / judgment trace / checkpoints / learning memories
- `src/main/java/com/agentcloud/model/TaskLiveFlowView.java`
  - 当前还没有 tool trace 字段
- `src/main/java/com/agentcloud/server/NioHttpServer.java`
  - 当前已经注册 `/tasks`、`/sessions`、`/workers`、`/skills`、`/checkpoints`、`/learning_memories`
  - 新增 tool trace API 时，应沿用同样的 handler 模式

这几个点决定了后续实现策略：

- `ControlNodeGraph` 尽量不感知 tool-aware 细节
- `WorkerExecutorRouter` 最好自己实现 `WorkerExecutor`
- `ToolAwareWorkerExecutor` 只负责一轮 worker 执行中的工具选择与工具回填
- tool trace 应先做独立 API，再并入 `live_flow`

---

## 3. 第 4 步：先补 Tool Trace API

### 3.1 目标

在 worker 真正开始调工具之前，先让系统具备以下能力：

- 按 task 查看最近工具调用记录
- 在 live validation 中快速判断：
  - worker 有没有触发工具调用
  - 调了哪个工具
  - 调用成功还是失败
  - scope/policy 是否拦截了调用

这一步不要求 executor 已经接工具，只要求观测面先准备好。

### 3.2 建议新增文件

- `src/main/java/com/agentcloud/server/ToolInvocationHandler.java`

### 3.3 建议 API

第一版建议只做一个入口，保持简单：

- `GET /api/v1/tasks/{id}/tool_trace`

返回值直接用 `ApiResponse<List<ToolInvocationRecord>>` 即可，不急着单独做 view model。

如果想和现有资源风格保持一致，也可以再补一个：

- `GET /api/v1/tool_invocations/{taskId}`

但第一版只做 `/tasks/{id}/tool_trace` 已足够。

### 3.4 Handler 最小逻辑

`ToolInvocationHandler` 建议行为：

- 只处理 `GET`
- 从路径中取 `taskId`
- 支持 `limit` 查询参数
- 调 `TaskService` 或 `ToolInvocationDao` 查询最近记录
- 返回统一 `ApiResponse.ok(list)`

建议优先让 `TaskService` 暴露一个方法，而不是让 handler 直接拿 DAO。

建议新增：

- `TaskService.listToolInvocations(String taskId, int limit)`

这样后续如果要在 service 层做权限、排序、聚合，不需要再改 handler。

### 3.5 对现有类的修改建议

#### `TaskService`

建议新增字段：

- `ToolInvocationDao toolInvocationDao`

建议新增方法：

- `public List<ToolInvocationRecord> listToolInvocations(String taskId, int limit)`

逻辑：

1. 先校验 task 是否存在
2. 调 `toolInvocationDao.listByTask(taskId, boundedLimit(limit))`

#### `NioHttpServer`

建议新增依赖：

- `ToolInvocationDao` 或 `TaskService` 已足够时不单独依赖 DAO

建议注册 context：

- `server.createContext("/api/v1/tasks", new TaskHandler(...))`
- `server.createContext("/api/v1/tasks/", ...)` 不需要额外注册，因为当前 `TaskHandler` 已处理子路径

这里更自然的做法是直接把 `/tool_trace` 继续放进现有 `TaskHandler`，而不是单独新顶级 context。理由：

- 当前 `/tasks/{id}/packet`、`/runtime_context`、`/judgment_trace`、`/live_flow` 都在 `TaskHandler`
- `/tasks/{id}/tool_trace` 语义上也是 task 级观测面

因此更推荐：

- 修改 `TaskHandler`
- 在 `GET /api/v1/tasks/{id}/tool_trace` 分支中调用 `taskService.listToolInvocations`

也就是说，这一步不一定要新建 `ToolInvocationHandler`。如果目标是保持当前 API 风格一致，直接扩 `TaskHandler` 更自然。

### 3.6 推荐取舍

本项目目前更适合下面这个方案：

- 不新增独立 `ToolInvocationHandler`
- 直接在 `TaskHandler` 增加 `/tool_trace`
- `TaskService` 新增 `listToolInvocations`

这样改动最小，也最符合当前代码风格。

### 3.7 验收标准

满足以下条件即可：

1. `GET /api/v1/tasks/{id}/tool_trace` 可返回空列表
2. 当 `tool_invocations` 表中有数据时，能按时间顺序返回最近记录
3. 非存在 task 返回明确错误
4. 不影响现有 `/runtime_context`、`/judgment_trace`、`/live_flow`

### 3.8 方法级改动建议

这一节把第 4 步压到“具体改哪些方法”的粒度。

#### `TaskService`

建议新增字段：

- `private final ToolInvocationDao toolInvocationDao;`

建议构造函数新增参数：

- `ToolInvocationDao toolInvocationDao`

建议新增方法：

```java
public List<ToolInvocationRecord> listToolInvocations(String taskId, int limit)
```

最小逻辑：

1. `taskDao.findById(taskId)` 校验 task 存在
2. `toolInvocationDao.listByTask(taskId, boundedLimit(limit))`
3. 直接返回列表

这里不要先做复杂权限逻辑，因为当前项目没有认证体系。

#### `TaskHandler`

当前 `GET /api/v1/tasks/{id}/...` 子路径已经很多，因此建议继续在同一个分支里追加：

```java
} else if (path.endsWith("/tool_trace")) {
    Map<String, String> params = parseQuery(query);
    int limit = parseLimit(params.get("limit"));
    var trace = svc.listToolInvocations(id, limit);
    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(trace));
}
```

插入位置建议放在：

- `/live_flow` 之后
- `/handoff_packet` 之前

这样当前观测类接口会排在一起：

- `/runtime_context`
- `/judgment_trace`
- `/live_flow`
- `/tool_trace`

#### `Main`

在第 4 步本身还不需要构造 tool-aware executor，但建议先把 DAO 装配补上：

```java
ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
```

然后把它注入 `TaskService`。

### 3.9 返回格式建议

`ToolInvocationRecord` 第一版直接返回原对象即可，不必额外包 view model。

原因：

1. 当前其他观测接口如 `runtime_context`、`judgment_trace` 也基本直接返回聚合对象
2. tool trace 目前主要服务于开发和 live validation
3. 过早引入 view model 只会增加维护成本

建议 `metadata` 内预留几个字段，方便后面排障：

- `policy_denied`
- `error_type`
- `tool_scope`
- `path`

即使第一版还没完全用上，也值得先统一约定。

---

## 4. 第 5 步：引入 WorkerExecutorRouter

### 4.1 目标

在不修改 `ControlNodeGraph` 行为的前提下，把执行器从“一个实现”升级成“按 worker 合同动态分派的统一门面”。

当前 `ControlNodeGraph` 已经依赖 `WorkerExecutor` 接口，因此最自然的实现不是改控制图，而是新增一个实现了 `WorkerExecutor` 的路由器。

### 4.2 建议新增文件

- `src/main/java/com/agentcloud/worker/WorkerExecutorRouter.java`

### 4.3 设计原则

`WorkerExecutorRouter` 应该自己实现 `WorkerExecutor`：

```java
public class WorkerExecutorRouter implements WorkerExecutor {
    @Override
    public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
        ...
    }
}
```

这样 `ControlNodeGraph` 完全不用改签名，只需要在 `Main` 里把注入对象从 `DefaultWorkerExecutor` 换成 `WorkerExecutorRouter`。

### 4.4 依赖建议

`WorkerExecutorRouter` 建议持有：

- `WorkerRegistry workerRegistry`
- `WorkerExecutor defaultExecutor`
- `WorkerExecutor toolAwareExecutor`

如果后续还有 codex-thread bridge executor，也可以继续往这里加分派逻辑。

### 4.5 分派规则

第一版规则建议非常直接：

1. 找到 `workerRegistry.get(workerId)`
2. 如果 worker 不存在：
   - 记录 warning
   - 回退到 `defaultExecutor`
3. 如果 `worker.suggestOnly()` 为 `true`：
   - 使用 `defaultExecutor`
4. 如果 `worker.toolCapabilities()` 为空：
   - 使用 `defaultExecutor`
5. 否则：
   - 使用 `toolAwareExecutor`

也就是说：

- `suggestOnly` 是总开关
- `toolCapabilities` 是是否进入 tool-aware 模式的第二个条件

### 4.6 为什么这样设计

这样做的好处有三点：

1. 对 `ControlNodeGraph` 无侵入
2. 后面如果增加 `CodexThreadExecutor`，仍然可继续放进同一个 router
3. 可把 “worker contract -> executor choice” 明确收束到一个地方

### 4.7 对现有类的修改建议

#### `Main`

当前：

- `WorkerExecutor workerExecutor = new DefaultWorkerExecutor(llmClient);`

建议改成：

1. 构造 `DefaultWorkerExecutor`
2. 构造 `ToolAwareWorkerExecutor`
3. 构造 `WorkerExecutorRouter`
4. 把 `WorkerExecutorRouter` 注入 `ControlNodeGraph`

例如概念上应接近：

```java
WorkerExecutor defaultExecutor = new DefaultWorkerExecutor(llmClient);
WorkerExecutor toolAwareExecutor = new ToolAwareWorkerExecutor(...);
WorkerExecutor workerExecutor = new WorkerExecutorRouter(workerRegistry, defaultExecutor, toolAwareExecutor);
```

#### `WorkerRegistry`

需要确保：

- `get(workerId)` 正常返回包含 `toolCapabilities/toolScope/suggestOnly` 的新 `Worker`

不需要修改 `WorkerRouter` 的路由逻辑。路由负责“选谁”，执行器路由负责“怎么执行”。

### 4.8 验收标准

满足以下条件即可：

1. 现有无工具 worker 任务行为不变
2. `kimi-local-doc` 这类声明了工具能力的 worker 能被分派到 `ToolAwareWorkerExecutor`
3. worker 不存在时系统不崩，仍可回退到默认执行器

### 4.9 方法级改动建议

#### `WorkerExecutorRouter`

建议结构：

```java
public class WorkerExecutorRouter implements WorkerExecutor {
    private final WorkerRegistry workerRegistry;
    private final WorkerExecutor defaultExecutor;
    private final WorkerExecutor toolAwareExecutor;

    @Override
    public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
        ...
    }
}
```

建议内部再拆一个方法：

```java
private WorkerExecutor selectExecutor(String workerId)
```

这样 `executeOneRound(...)` 只负责：

1. 选择执行器
2. 委派执行

而选择逻辑单独收敛在 `selectExecutor(...)` 中。

#### `Main`

第 5 步落地时，`Main` 里建议变成下面这个装配顺序：

1. `WorkerRegistry`
2. `ToolRegistry`
3. `ToolPolicy`
4. `DefaultWorkerExecutor`
5. `ToolAwareWorkerExecutor`
6. `WorkerExecutorRouter`
7. `ControlNodeGraph`

原因是：

- `ControlNodeGraph` 只依赖最终统一的 `WorkerExecutor`
- `ToolAwareWorkerExecutor` 依赖 tool 基础设施
- `WorkerExecutorRouter` 依赖两个具体执行器和 `WorkerRegistry`

#### `ControlNodeGraph`

这一阶段建议完全不动。

如果你在这一阶段开始往 `ControlNodeGraph` 里塞：

- if worker supports tools
- if suggest_only
- if tool_scope

那后面很快会把控制图污染成执行策略层。这个分层应避免。

---

## 5. 第 6 步：实现 ToolAwareWorkerExecutor

### 5.1 目标

让一个声明了工具能力的纯 LLM worker，能够在一轮执行中：

1. 先判断需不需要工具
2. 选择一个工具和参数
3. 执行工具
4. 记录 tool trace
5. 根据工具结果再生成最终 `WorkerExecutionResult`

第一版不要追求多工具循环、复杂 planner、函数调用标准兼容。先收一个最小闭环：

```text
llm plan
  -> maybe tool
  -> invoke tool once
  -> llm finalize
  -> return WorkerExecutionResult
```

### 5.2 建议新增文件

- `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java`

如果希望把中间协议显式化，建议再加两个内部 record 或独立 model：

- `src/main/java/com/agentcloud/worker/model/ToolPlan.java`
- `src/main/java/com/agentcloud/worker/model/ToolFinalPayload.java`

但第一版也可以先作为 `ToolAwareWorkerExecutor` 内部私有静态 record。

### 5.3 建议依赖

`ToolAwareWorkerExecutor` 建议持有：

- `LlmClient llmClient`
- `WorkerRegistry workerRegistry`
- `ToolRegistry toolRegistry`
- `ToolPolicy toolPolicy`
- `ToolInvocationDao toolInvocationDao`

### 5.4 最小双阶段协议

第一版建议使用两次 LLM 调用，而不是把所有事塞进一次 prompt。

#### 阶段 A：Tool Planning

先让模型只回答一件事：

- 这次是否需要工具
- 如果需要，用哪个工具
- 传什么参数

建议输出 JSON：

```json
{
  "needs_tool": true,
  "tool_name": "read_file",
  "tool_arguments": {
    "path": "D:/..."
  },
  "reason": "需要先读取最近更新的文档后才能总结"
}
```

约束：

- 只能从 worker 已声明的 `toolCapabilities` 中选
- 不要直接让模型返回最终答案
- 输出必须是 JSON

#### 阶段 B：Final Execution

如果 `needs_tool = false`：

- 直接再走一次最终执行输出

如果 `needs_tool = true`：

1. 先经过 `ToolPolicy`
2. 调 `ToolRegistry.get(toolName)`
3. 执行工具
4. 记录 `tool_invocations`
5. 把 tool result 作为额外上下文喂给模型
6. 让模型输出标准 `WorkerExecutionResult` JSON

建议最终输出仍沿用当前 `DefaultWorkerExecutor` 的结构：

```json
{
  "summary": "string",
  "output_text": "string",
  "produced_artifact": false,
  "artifact_title": "",
  "artifact_content": "",
  "suggested_next_step": "string",
  "confidence": "high|medium|low"
}
```

### 5.5 为什么用双阶段而不是一次完成

原因有三点：

1. 容易调试
   - 可以明确知道是 plan 错了，还是 tool 执行错了，还是 finalization 错了
2. 容易记录 trace
   - tool selection、tool execution、final payload 都能分开观察
3. 更适合当前项目
   - 当前代码已经是“显式步骤 + 显式记录”的风格，不适合一开始就把工具调用隐藏到黑盒 function-calling

### 5.6 最小实现细节

#### 5.6.1 获取 worker 合同

执行开始时先：

- `Worker worker = workerRegistry.get(workerId);`

如果 worker 为空：

- 回退到 `DefaultWorkerExecutor`
- 或抛出受控异常后由 router 回退

更推荐在 `WorkerExecutorRouter` 中已经处理不存在 worker 的情况，这里默认拿到的是存在且已声明工具能力的 worker。

#### 5.6.2 Tool planning prompt

planning prompt 只关注：

- task title / goal / intent
- active context 摘要
- worker 允许使用的工具列表
- worker 允许访问的 scope

不要在 planning 阶段塞太多 recent artifacts 全量内容，否则模型容易直接输出结论，忽略工具调用。

#### 5.6.3 Tool invocation

执行工具时建议流程：

1. 记录开始时间
2. `toolPolicy.ensureToolAllowed(worker, toolName)`
3. 对含路径参数的工具调用 `resolveAllowedPath(...)`
4. `toolRegistry.get(toolName).invoke(request)`
5. 写 `tool_invocations`

写 trace 时至少记录：

- `worker_id`
- `tool_name`
- `arguments_json`
- `result_summary`
- `success`
- `elapsed_ms`
- `metadata_json`

#### 5.6.4 Finalization prompt

finalization prompt 应包含：

- 原始 task core
- synthesized active context
- tool invocation summary
- tool result output

要求模型：

- 生成最终 worker round 结果
- 不再重复规划工具
- 严格输出标准 JSON

#### 5.6.5 失败处理

第一版必须明确几类失败路径：

1. planning JSON 解析失败
   - 回退到 `DefaultWorkerExecutor`
2. 选择了未声明工具
   - 记录失败 trace
   - 返回一个低置信度结果，说明工具策略拒绝
3. 路径越界
   - 记录失败 trace
   - 返回低置信度结果
4. tool 执行异常
   - 记录失败 trace
   - 允许 finalization 根据失败摘要输出可读结论
5. final payload 解析失败
   - 退回 raw text fallback，风格可参考 `DefaultWorkerExecutor`

第一版不建议把任何 tool 调用异常直接冒泡到 `ControlNodeGraph`，否则一轮 worker execution 很容易把整个请求打成 500。

### 5.7 对现有类的关系

#### `DefaultWorkerExecutor`

不建议把工具能力直接硬塞进去。

它应该继续保持：

- suggest-only worker 的执行器
- 无工具 worker 的默认执行器
- final payload 解析逻辑的参考实现

#### `ControlNodeGraph`

第一版不建议增加任何 tool-aware 分支。

它只应继续看到一个统一的 `WorkerExecutionResult`。

#### `TaskService`

在这一阶段不需要感知 tool-aware 细节，只需要后续在 `getLiveFlow()` 中增加 tool trace 聚合。

### 5.8 建议的最小辅助方法

`ToolAwareWorkerExecutor` 内建议收成这些私有方法：

- `private ToolPlan planToolUsage(TaskRuntimeContext context, Worker worker)`
- `private ToolResult executeTool(Worker worker, ToolPlan plan, TaskRuntimeContext context)`
- `private WorkerExecutionResult finalizeExecution(TaskRuntimeContext context, Worker worker, ToolPlan plan, ToolResult toolResult)`
- `private void recordToolInvocation(...)`
- `private WorkerExecutionResult fallbackResult(String message, long durationMs, Map<String, Object> metadata)`

这样类内职责会比较清晰。

### 5.9 验收标准

满足以下条件即可：

1. `kimi-local-doc` 可完成一次 `read_file` 或 `search_text` 调用
2. 调用结果会写入 `tool_invocations`
3. tool 成功后能产出标准 `WorkerExecutionResult`
4. tool 失败时不会让任务创建接口直接 500
5. 现有无工具 worker 不受影响

### 5.10 中间协议建议

为了让 `ToolAwareWorkerExecutor` 足够稳定，第一版建议把 planning 和 finalization 的 JSON 协议先固定下来。

#### `ToolPlan`

建议字段：

```json
{
  "needs_tool": true,
  "tool_name": "read_file",
  "tool_arguments": {
    "path": "D:/..."
  },
  "reason": "需要先读取最近更新文档",
  "fallback_answer": ""
}
```

说明：

- `needs_tool`
  - 是否真的要调工具
- `tool_name`
  - 只能从 worker 的 `toolCapabilities` 中选
- `tool_arguments`
  - 先只支持一层对象，不做嵌套复杂结构
- `reason`
  - 方便 trace 和排障
- `fallback_answer`
  - 当模型判断无需工具时，允许顺手给一段简短备用答案，但第一版可以不使用

#### `ToolFinalPayload`

建议直接复用当前 `WorkerExecutionResult` 的解析格式：

```json
{
  "summary": "string",
  "output_text": "string",
  "produced_artifact": false,
  "artifact_title": "",
  "artifact_content": "",
  "suggested_next_step": "string",
  "confidence": "high|medium|low"
}
```

也就是说：

- planning 阶段只负责判断“要不要工具”
- finalization 阶段只负责输出最终 worker round 结果

不要让这两个阶段混用一个 JSON，否则很难稳定解析。

### 5.11 Prompt 组织建议

#### Planning Prompt

system prompt 建议明确三件事：

1. 你是 tool planning assistant，不是 final executor
2. 你只能从给定工具名单中选择一个工具，或明确不需要工具
3. 你必须输出固定 JSON，不允许 markdown，不允许额外解释

user prompt 建议包含：

- task title / goal / intent
- active context 摘要
- allowed tools
- allowed scope

不要在 planning prompt 里塞入过多 recent event/artifact 原文，否则模型容易跳过工具选择，直接给结论。

#### Finalization Prompt

system prompt 建议明确：

1. 你正在基于已有 tool result 生成本轮最终执行结果
2. 不要再规划工具
3. 只返回 `WorkerExecutionResult` JSON

user prompt 建议包含：

- task core
- active context
- tool plan summary
- tool result summary
- tool result output

### 5.12 Tool Result 到 Trace 的映射建议

第一版 `tool_invocations.metadata_json` 建议至少统一记录：

- `phase`
  - `planning` 不写表
  - `execution` 写表
- `tool_scope`
- `tool_arguments`
- `tool_output_truncated`
- `policy_denied`
- `error_message`

其中：

- 成功执行时，`result_summary` 存工具摘要
- 失败执行时，`result_summary` 存失败摘要
- 详细异常放 `metadata.error_message`

这样后面看 `/tool_trace` 时，不需要再去翻日志。

### 5.13 降级策略建议

第一版建议所有异常都尽量在 `ToolAwareWorkerExecutor` 内部降级，而不是抛到控制图：

#### 场景 1：planning 失败

- 解析 JSON 失败
- 模型输出空白

处理：

- 记录 warn 日志
- 回退到 `DefaultWorkerExecutor`

#### 场景 2：tool policy 拒绝

- tool 不在 `toolCapabilities`
- path 不在 `toolScope`
- worker 为 `suggestOnly`

处理：

- 记录失败 trace
- 返回低置信度 `WorkerExecutionResult`
- `metadata` 标 `tool_policy_denied=true`

#### 场景 3：tool 执行异常

处理：

- 记录失败 trace
- 允许 finalization 根据错误摘要生成可读结论
- 若 finalization 再失败，回退 fallback result

#### 场景 4：finalization 失败

处理：

- 使用和 `DefaultWorkerExecutor` 相同的 raw text fallback 风格
- `metadata.parser = raw_text`
- `metadata.tool_aware = true`

这样做的目标很明确：

- worker round 最多质量下降
- 不要把 HTTP 请求直接打成 500

### 5.14 建议的最小测试顺序

第 6 步完成后，建议按下面顺序验：

1. `planning` 返回 `needs_tool=false`
   - 应直接走最终输出
2. `planning` 返回 `needs_tool=true` 且工具成功
   - 应写入一条成功 trace
   - 应生成最终 `WorkerExecutionResult`
3. `planning` 返回非法工具名
   - 应写入失败 trace
   - 不应让请求 500
4. `planning` 返回 scope 外路径
   - 应写入失败 trace
   - 应返回低置信度结果
5. `planning` 返回合法工具但工具执行抛错
   - 应写入失败 trace
   - 应有可读 fallback

这 5 条如果都能稳定通过，说明第 6 步已经不是“概念可行”，而是“运行可控”。

---

## 6. 第 6 步之后的紧邻改动

在 `ToolAwareWorkerExecutor` 跑通后，建议立即补两件事。

### 6.1 把 tool trace 接入 live flow

需要修改：

- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/main/java/com/agentcloud/model/TaskLiveFlowView.java`

建议新增字段：

- `List<ToolInvocationRecord> toolInvocations`

`getLiveFlow()` 中增加：

- `toolInvocationDao.listByTask(taskId, boundedLimit(limit))`

这样 live validation 时，一个接口就能看到：

- route
- runtime context
- judgment
- checkpoints
- learning memory
- tool trace

### 6.2 增加文档与排障说明

建议同步更新：

- `docs/API_CONTRACTS.md`
- `docs/ARCHITECTURE.md`
- `docs/SPEC.md`
- `docs/TROUBLESHOOT.md`

至少要补：

- `/api/v1/tasks/{id}/tool_trace`
- `Worker` 新字段含义
- tool-aware worker 与 suggest-only worker 的差异
- scope 越界、tool not allowed、tool JSON parse failure 的排障方式

### 6.3 推荐先后顺序

如果准备真正开工第 4-6 步，建议严格按下面顺序推进：

1. 先加 `TaskService.listToolInvocations(...)`
2. 再改 `TaskHandler`，开放 `/tool_trace`
3. 再加 `WorkerExecutorRouter`
4. 再接 `ToolAwareWorkerExecutor` 骨架，但先只做回退
5. 最后补 planning / invocation / finalization 三段逻辑

原因：

- 第 4 步先完成后，后面一旦开始调工具，排障面就已经存在
- `WorkerExecutorRouter` 先就位后，`ControlNodeGraph` 就不会在后续提交里来回改
- `ToolAwareWorkerExecutor` 先做骨架再补逻辑，能减少一次性引入太多变量

---

## 7. 推荐提交顺序

按风险最低的顺序，建议拆成下面几个提交：

1. `add-tool-trace-query-api`
2. `add-worker-executor-router`
3. `add-tool-aware-worker-executor`
4. `aggregate-tool-trace-into-live-flow`
5. `document-tool-policy-and-debugging`

这样每一步都能独立编译、独立回归、独立验证。

---

## 8. 一句话判断

第 4-6 步的核心，不是“让 LLM 学会调工具”，而是：

**在不破坏现有 control graph 的前提下，把工具调用变成一种受 worker 合同约束、可追踪、可回放、可降级的执行路径。**

---

## 9. 当前仓库里的具体改造风险

这一节不再讲抽象设计，只讲当前源码里真正会卡手的地方。

### 9.1 `Worker` record 一改，三个入口会立刻跟着变

当前 `src/main/java/com/agentcloud/model/Worker.java` 还是旧签名：

```java
public record Worker(
    String workerId,
    String workerType,
    List<String> capabilities,
    Map<String, Boolean> dependencies,
    Map<String, Object> metadata,
    boolean ready
) {}
```

只要加上：

- `List<String> toolCapabilities`
- `List<String> toolScope`
- `boolean suggestOnly`

下面三个位置就必须同一批改掉：

1. `src/main/java/com/agentcloud/engine/router/WorkerRegistry.java`
2. `src/main/java/com/agentcloud/server/WorkerHandler.java`
3. 所有手写 `new Worker(...)` 的地方

如果只改 record，不改 registry / handler，项目会直接编译不过。

### 9.2 `WorkerRegistry` 不是只改字段，还要重定义内置 worker 语义

当前 `WorkerRegistry` 里预注册了：

- `openclaw-native`
- `codex`
- `kimi`

它们现在只有：

- `capabilities`
- `dependencies`
- `metadata`
- `ready`

一旦引入工具合同，建议立即同步把语义拆清：

- `openclaw-native`
  - `toolCapabilities = []`
  - `toolScope = []`
  - `suggestOnly = false`
- `codex`
  - `toolCapabilities = []`
  - `toolScope = []`
  - `suggestOnly = false`
- `kimi`
  - `toolCapabilities = []`
  - `toolScope = []`
  - `suggestOnly = true`
- `kimi-local-doc`
  - 第一版试点 worker
  - 只开放文档目录内的文件工具

也就是说，`WorkerRegistry` 不只是“补几个默认值”，而是正式把 worker 分层落到代码里。

### 9.3 `WorkerHandler` 当前是直接裸转 map，签名变更后最容易埋雷

当前 `WorkerHandler` 的注册逻辑还是这种风格：

```java
Map<String, Object> body = mapper.readValue(..., Map.class);
var w = new Worker(
    body.get("worker_id").toString(),
    body.getOrDefault("worker_type", "other").toString(),
    (List<String>) body.getOrDefault("capabilities", List.of()),
    (Map<String, Boolean>) body.getOrDefault("dependencies", Map.of()),
    (Map<String, Object>) body.getOrDefault("metadata", Map.of()),
    true
);
```

这意味着一旦扩展字段，最稳的做法不是继续横向复制强转，而是顺手补几个 helper：

- `requiredString(...)`
- `optionalString(...)`
- `stringList(...)`
- `booleanMap(...)`
- `objectMap(...)`
- `optionalBoolean(...)`

否则：

- `tool_scope` 传空值时可能直接 `ClassCastException`
- `suggest_only` 若是字符串 `"true"` 也会很脆
- 错误信息会继续原样打到 500 响应里

### 9.4 `TaskHandler` 加 `/tool_trace` 时，最好保持现有子资源顺序

当前 `TaskHandler` 的 task 子资源顺序大致是：

- `/packet`
- `/refresh_packet`
- `/select_worker`
- `/runtime_context`
- `/judgment_trace`
- `/live_flow`
- `/handoff_packet`
- `/pause`
- `/resume`
- `/continue`
- `/escalate`

因此 `/tool_trace` 最自然的位置是：

- `/live_flow` 之后
- `/handoff_packet` 之前

这样一眼看上去就能把“观测类接口”收在一起：

- `/runtime_context`
- `/judgment_trace`
- `/live_flow`
- `/tool_trace`

### 9.5 当前 `TaskHandler.parseLimit()` 已经能复用，不必重写

`TaskHandler` 里已经有：

- `parseQuery(String query)`
- `parseLimit(String raw)`

所以 `/tool_trace` 第一版完全可以直接复用现有 limit 解析逻辑。

默认值建议继续沿用：

- 默认 `5`
- 最小 `1`
- 最大 `20`

这样和 `/live_flow` 的交互体验一致。

### 9.6 `LlmClient` 目前只有 `chat(system, user)`，不要假设有 messages/tool-call 能力

当前 `src/main/java/com/agentcloud/llm/LlmClient.java` 和 `OpenAiCompatibleClient.java` 的能力非常简单：

- 只支持一次 system prompt
- 一次 user prompt
- 返回纯字符串

没有：

- function calling
- response schema
- tool call DSL
- 多消息轮对话状态管理

因此 `ToolAwareWorkerExecutor` 必须按下面思路设计：

1. planning：一次普通 `chat`
2. tool invoke：本地执行
3. finalization：第二次普通 `chat`

如果需要 third-step fallback，也只能继续用普通 `chat`，不能假设底层 SDK 帮你做工具编排。

### 9.7 `DefaultWorkerExecutor` 已经给了两个很值得复用的模式

当前 `DefaultWorkerExecutor` 已经有两套成熟模式，可以直接借：

1. prompt 组织模式
   - system prompt 明确固定 JSON 契约
   - user prompt 用 task core + active context + recent context
2. JSON 解析失败后的 raw-text fallback
   - parse 失败不抛异常
   - 退回 `WorkerExecutionResult`
   - `metadata.parser = raw_text`

`ToolAwareWorkerExecutor` 第一版最好完全沿用这两个原则，不要另起一套风格。

### 9.8 如果顺手碰 handler，最好把错误泄露一起收一层

当前 `TaskHandler` 和 `WorkerHandler` 的 `catch` 还是：

```java
NioHttpServer.sendJson(ex, 500, ApiResponse.error("500", e.getMessage()));
```

如果你在实现工具层时本来就要碰这些 handler，建议最低限度收一层：

- 日志里保留异常明细
- HTTP 500 返回固定文案，例如 `internal error`

这不是工具层的主目标，但这是低成本顺手修复项。

---

## 10. 可直接照写的代码骨架

这一节给的是“贴着当前仓库写”的骨架，不是抽象伪码。

### 10.1 `WorkerExecutorRouter` 骨架

建议文件：

- `src/main/java/com/agentcloud/worker/WorkerExecutorRouter.java`

建议结构：

```java
package com.agentcloud.worker;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkerExecutorRouter implements WorkerExecutor {
    private static final Logger log = LoggerFactory.getLogger(WorkerExecutorRouter.class);

    private final WorkerRegistry workerRegistry;
    private final WorkerExecutor defaultExecutor;
    private final WorkerExecutor toolAwareExecutor;

    public WorkerExecutorRouter(
        WorkerRegistry workerRegistry,
        WorkerExecutor defaultExecutor,
        WorkerExecutor toolAwareExecutor
    ) {
        this.workerRegistry = workerRegistry;
        this.defaultExecutor = defaultExecutor;
        this.toolAwareExecutor = toolAwareExecutor;
    }

    @Override
    public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
        WorkerExecutor executor = selectExecutor(workerId);
        return executor.executeOneRound(context, workerId);
    }

    private WorkerExecutor selectExecutor(String workerId) {
        Worker worker = workerRegistry.get(workerId);
        if (worker == null) {
            log.warn("Worker not found, fallback to default executor. worker={}", workerId);
            return defaultExecutor;
        }
        if (worker.suggestOnly()) {
            return defaultExecutor;
        }
        if (worker.toolCapabilities() == null || worker.toolCapabilities().isEmpty()) {
            return defaultExecutor;
        }
        return toolAwareExecutor;
    }
}
```

这里的关键不是代码量，而是职责边界：

- router 只决定“谁执行”
- 不做 tool policy
- 不碰 tool trace
- 不碰 prompt

### 10.2 `ToolAwareWorkerExecutor` 骨架

建议文件：

- `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java`

建议结构：

```java
package com.agentcloud.worker;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.llm.LlmClient;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.tool.Tool;
import com.agentcloud.tool.ToolPolicy;
import com.agentcloud.tool.ToolRegistry;
import com.agentcloud.tool.ToolRequest;
import com.agentcloud.tool.ToolResult;
import com.agentcloud.store.ToolInvocationDao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class ToolAwareWorkerExecutor implements WorkerExecutor {
    private static final Logger log = LoggerFactory.getLogger(ToolAwareWorkerExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient llmClient;
    private final WorkerRegistry workerRegistry;
    private final ToolRegistry toolRegistry;
    private final ToolPolicy toolPolicy;
    private final ToolInvocationDao toolInvocationDao;
    private final WorkerExecutor fallbackExecutor;

    public ToolAwareWorkerExecutor(
        LlmClient llmClient,
        WorkerRegistry workerRegistry,
        ToolRegistry toolRegistry,
        ToolPolicy toolPolicy,
        ToolInvocationDao toolInvocationDao,
        WorkerExecutor fallbackExecutor
    ) {
        this.llmClient = llmClient;
        this.workerRegistry = workerRegistry;
        this.toolRegistry = toolRegistry;
        this.toolPolicy = toolPolicy;
        this.toolInvocationDao = toolInvocationDao;
        this.fallbackExecutor = fallbackExecutor;
    }

    @Override
    public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
        long startMs = System.currentTimeMillis();
        Worker worker = workerRegistry.get(workerId);
        if (worker == null) {
            return fallbackExecutor.executeOneRound(context, workerId);
        }

        try {
            ToolPlan plan = planToolUsage(context, worker);
            if (!plan.needsTool()) {
                return finalizeWithoutTool(context, worker, plan, startMs);
            }

            ToolResult toolResult = executeTool(context, worker, plan);
            return finalizeWithTool(context, worker, plan, toolResult, startMs);
        } catch (Exception e) {
            log.warn("Tool-aware execution failed, fallback to default executor. task={}, worker={}, message={}",
                context.task().id(), workerId, e.toString());
            return fallbackExecutor.executeOneRound(context, workerId);
        }
    }

    private ToolPlan planToolUsage(TaskRuntimeContext context, Worker worker) {
        ...
    }

    private ToolResult executeTool(TaskRuntimeContext context, Worker worker, ToolPlan plan) {
        ...
    }

    private WorkerExecutionResult finalizeWithoutTool(
        TaskRuntimeContext context,
        Worker worker,
        ToolPlan plan,
        long startMs
    ) {
        ...
    }

    private WorkerExecutionResult finalizeWithTool(
        TaskRuntimeContext context,
        Worker worker,
        ToolPlan plan,
        ToolResult toolResult,
        long startMs
    ) {
        ...
    }

    private void recordToolInvocation(...) {
        ...
    }

    private WorkerExecutionResult fallbackResult(...) {
        ...
    }

    private record ToolPlan(
        boolean needsTool,
        String toolName,
        Map<String, Object> toolArguments,
        String reason,
        String fallbackAnswer
    ) {}
}
```

### 10.3 `ToolPlan` 解析逻辑建议直接模仿 `DefaultWorkerExecutor`

最稳的写法不是一次性追求强 schema，而是复用当前 parse 风格：

```java
private ToolPlan parseToolPlan(String raw) {
    String safeRaw = raw == null ? "" : raw.trim();
    if (safeRaw.isBlank()) {
        return new ToolPlan(false, "", Map.of(), "empty planning response", "");
    }

    try {
        JsonNode json = MAPPER.readTree(safeRaw);
        boolean needsTool = json.path("needs_tool").asBoolean(false);
        String toolName = json.path("tool_name").asText("");
        String reason = json.path("reason").asText("");
        String fallbackAnswer = json.path("fallback_answer").asText("");
        Map<String, Object> arguments = MAPPER.convertValue(
            json.path("tool_arguments"),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
        );
        return new ToolPlan(needsTool, toolName, arguments == null ? Map.of() : arguments, reason, fallbackAnswer);
    } catch (Exception e) {
        log.warn("Failed to parse tool plan JSON, fallback to no-tool plan: {}", e.toString());
        return new ToolPlan(false, "", Map.of(), "planning parse failure", safeRaw);
    }
}
```

这个处理方式和 `DefaultWorkerExecutor.parseExecutionResult(...)` 的风格是一致的：

- 先吃掉空输出
- 再尝试 JSON 解析
- 失败时给受控 fallback，而不是把异常抛出去

### 10.4 最终结果解析最好直接复用 `DefaultWorkerExecutor` 的思路

建议不要在 `ToolAwareWorkerExecutor` 里重新发明一套结果协议。

最简单的做法有两个：

1. 把 `DefaultWorkerExecutor.parseExecutionResult(...)` 抽成包内可复用 helper
2. 或在 `ToolAwareWorkerExecutor` 内复制一份同风格实现

如果暂时不想动 `DefaultWorkerExecutor` 可见性，复制一份同风格方法是可以接受的；第一版优先稳定，不必为了“去重复”过早重构。

### 10.5 planning prompt 建议模板

由于当前 `LlmClient` 只有 `chat(system, user)`，planning prompt 最好写得非常刚。

建议 `system prompt`：

```text
You are a tool planning assistant for one worker round.
You are not the final executor.
Choose at most one tool from the allowed tool list, or decide no tool is needed.
Return a JSON object with exactly these fields:
needs_tool (boolean), tool_name (string), tool_arguments (object), reason (string), fallback_answer (string).
No markdown. No extra text.
```

建议 `user prompt` 包含：

- `Task Title`
- `Goal`
- `Intent`
- `Next Step`
- `Active Context`
- `Allowed Tools`
- `Allowed Scope`

同时显式提醒：

- 若无需工具，`needs_tool=false`
- 若需要工具，只能选一个工具
- 不要直接返回最终执行结果

### 10.6 finalization prompt 建议模板

建议 `system prompt`：

```text
You are producing the final output for one worker round.
Do not plan tools.
Based on the task context and the provided tool result, return a JSON object containing exactly these fields:
summary (string), output_text (string), produced_artifact (boolean), artifact_title (string),
artifact_content (string), suggested_next_step (string), confidence (high|medium|low).
No markdown. No extra text.
```

建议 `user prompt` 包含：

- task core
- active context
- tool plan summary
- tool result summary
- tool result output

如果 planning 判断 `needs_tool=false`，这里也可以复用同一套 finalization prompt，只是把：

- `tool plan summary` 标为 `no tool used`
- `tool result` 留空

这样可以减少 prompt 分叉。

### 10.7 `recordToolInvocation(...)` 建议最小字段

建议在 `recordToolInvocation(...)` 内统一组装：

- `id`
- `sessionId`
- `taskId`
- `workerId`
- `toolName`
- `arguments`
- `resultSummary`
- `success`
- `elapsedMs`
- `createdAt`
- `metadata`

其中 `metadata` 第一版建议统一带上：

- `reason`
- `tool_scope`
- `policy_denied`
- `error_message`
- `tool_output_truncated`

这样 `/tool_trace` 基本就够用了。

### 10.8 `fallbackResult(...)` 不要太复杂

第一版建议统一返回低置信度、可读、不中断的结果，例如：

```java
private WorkerExecutionResult fallbackResult(String message, long durationMs, Map<String, Object> metadata) {
    return new WorkerExecutionResult(
        message,
        message,
        false,
        "",
        "",
        "",
        "low",
        0,
        durationMs,
        metadata
    );
}
```

这里的目标不是“结果优雅”，而是：

- round 不崩
- judgment 还能继续工作
- live flow 里能看到失败语义

---

## 11. 开工前检查单

真正开始写代码前，建议先按下面顺序确认：

1. `Worker` record 的新字段和构造函数调用点是否已经统一改完
2. `schema.sql / ToolInvocationRecord / ToolInvocationDao / Mappers / DatabaseManager` 是否已齐
3. `TaskHandler` 的 `/tool_trace` 是否已先落好
4. `Main` 是否已经把 `ToolRegistry / ToolPolicy / ToolInvocationDao` 都装配出来
5. `WorkerExecutorRouter` 是否已接管 `ControlNodeGraph` 的执行器注入

只要这 5 项里有 2-3 项没齐，就不适合直接写 `ToolAwareWorkerExecutor` 主逻辑。

---

## 12. 下一步最自然的代码起点

如果准备从文档切到实现，最推荐的真实起点仍然是：

1. 先改 `Worker` 合同
2. 再补 `tool_invocations` 存储
3. 再加 `/api/v1/tasks/{id}/tool_trace`
4. 再接 `WorkerExecutorRouter`
5. 最后才写 `ToolAwareWorkerExecutor`

原因很直接：

- 这条顺序最符合当前仓库的耦合关系
- 每一步都能编译和回归
- 出问题时最容易定位到底是合同、存储、接口还是执行逻辑
