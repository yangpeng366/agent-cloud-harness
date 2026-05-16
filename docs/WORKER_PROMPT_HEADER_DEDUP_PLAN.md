# Worker Prompt Header 去重方案

## 背景

当前 worker 收到的任务包头，存在语义重复：

- `Task Title`
- `Goal`
- `Intent`
- `Active Context` 里的 `Task Focus`

在 continuation / real-project 场景下，这几个字段经常是同一句话的不同复制，导致：

- prompt 前几屏密度高但信息增量低
- worker 更像在读“重复任务单”，不是读可执行上下文
- 用户看到的 `Task Title / Goal / Task Focus / Intent` 也显得机械

## 目标

把 prompt header 收成“少量主语义 + 明确执行边界”，避免重复堆同一意图。

不做的事：

- 不让 LLM 在控制面里自由改写任务标题
- 不引入不可预测的 summarize step
- 不去掉 `Goal / Intent` 的结构化能力

本轮只做**确定性去重**。

## 作用范围

需要统一收口的代码面：

- `src/main/java/com/agentcloud/worker/ProviderTaskPromptBuilder.java`
- `src/main/java/com/agentcloud/worker/DefaultWorkerExecutor.java`
- `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java`
- `src/main/java/com/agentcloud/runtime/ActiveContextBuilder.java`

## 去重规则

### 1. `Task Title`

始终保留，作为顶层锚点。

### 2. `Goal`

仅在与 `Task Title` 语义不重复时保留。

最小规则：

- 规范化大小写、空白后
- 若 `goal == title`，则不再单独输出 `Goal`

### 3. `Intent`

仅在与 `Task Title / Goal` 都不重复时保留。

最小规则：

- 规范化大小写、空白后
- 若 `intent == title` 或 `intent == goal`，则不单独输出

### 4. `Task Focus`

`Active Context` 里的 `Task Focus` 不应只是 `goal/title/intent` 的再复制。

新的优先级：

1. `nextStep`
2. 与 `title/goal/intent` 不重复的明确 focus
3. 否则省略 `Task Focus` 这一行

也就是说，`Task Focus` 应该优先表示“当前轮真正要干什么”，而不是“整项任务是什么”。

## 实现策略

### A. 提供统一 header helper

由于 `worker prompt header` 和 `Active Context` 都需要同一套去重语义，实际落地时需要拆成两层：

- `runtime` 公共层：提供纯字符串去重 helper
- `worker` 包内：保留 header 组装 helper

也就是说，不能把去重原语只放在 `worker` 包里，否则 `ActiveContextBuilder` 会出现跨包依赖问题。

具体职责：

- `src/main/java/com/agentcloud/runtime/PromptFieldDeduper.java`
  - `normalizePromptField`
  - `isPromptFieldDuplicate`
  - `firstDistinctNormalized`
- `src/main/java/com/agentcloud/worker/WorkerPromptHeaderBuilder.java`
  - `appendTaskHeader`
  - 复用 `PromptFieldDeduper`，不再自己维护一套重复判断

这样 `ProviderTaskPromptBuilder`、`DefaultWorkerExecutor`、`ToolAwareWorkerExecutor`、`ActiveContextBuilder` 就能共用同一套去重规则。

### B. `ActiveContextBuilder` 做 Task Focus 去重

在生成 `synthesizedContext` 前，就把重复的 `Task Focus` 剪掉。

这样不仅 worker prompt 受益，前端看到的 `Active Context` 也会更合理。

## 预期结果

### 现状

可能出现：

- `Task Title: D:/gitAll/Articleeditor ...`
- `Goal: D:/gitAll/Articleeditor ...`
- `Intent: D:/gitAll/Articleeditor ...`
- `Task Focus: D:/gitAll/Articleeditor ...`

### 收口后

更接近：

- `Task Title: Articleeditor 修改轨迹页默认定位最后一版`
- `Goal: 通过 SecurityLayout 开关控制默认落到最后一版`
- `Active Context` 中仅保留真正不同的 `Task Focus / Next Candidates / Constraints`

## 验证口径

至少验证：

1. `title == goal == intent`
   - header 中只保留一份主语义
2. `goal` 与 `title` 不同，但 `intent` 重复
   - 保留 `Goal`，省略 `Intent`
3. `nextStep` 明确且不同
   - `Task Focus` 优先显示 `nextStep`
4. 旧 prompt contract 不破
   - `Priority / Task Type / Assigned Worker / Model Mode / Orchestration Stage` 仍正常输出

## 当前实现边界

当前这轮只做：

- 确定性字符串去重
- `Task Focus` 优先保留 `nextStep`
- 让 worker prompt 和 active context 共享同一去重语义

当前这轮不做：

- LLM 自动生成全新短标题
- 跨字段语义压缩或改写
- 基于任务类型的动态标题重写

## 备注

这轮目标是“去机械重复”，不是“让控制面替 LLM生成全新任务摘要”。后续如果要做更灵活的短标题生成，应单独做显式的 task header summarization 方案，不和本轮 deterministic dedupe 混在一起。
