# AHE 论文对 Agent Cloud Harness 的落地启发

来源：PaperWeekly 文章《Harness开始自己进化了：复旦×北大让Agent实现自改，10轮跑赢Codex》，论文为 Agentic Harness Engineering: Observability-Driven Automatic Evolution of Coding-Agent Harnesses。

## 1. 核心结论

这篇工作的关键启发不是“写更长的 Prompt”，而是把 Harness 本身当成可学习、可验证、可回滚的软件对象。

对本项目来说，Agent Cloud Harness 不应只做任务调度和 Agent Provider 接入，还应该逐步具备一套可观测驱动的自改进闭环：

1. 记录任务执行轨迹。
2. 从失败轨迹中抽取结构化根因。
3. 将经验沉淀到明确的 Harness 组件中。
4. 每次修改都带上预期修复项和潜在回归项。
5. 用下一轮评测验证修改是否兑现。
6. 对无效或引入回归的修改支持文件级回滚。

## 2. 对当前项目的直接映射

AHE 论文中的 Harness 组件，可以映射到本项目当前和计划中的模块：

| AHE 组件 | Agent Cloud Harness 中的落点 |
| --- | --- |
| 系统提示词 | Worker / Provider 的执行策略、任务说明模板、判断模板 |
| 工具实现 | Tool-aware execution、file/search/write/list 等工具层 |
| 中间件 | 路由、权限、上下文压缩、结果校验、重试/降级策略 |
| 长期记忆 | Learning Memory、routing preference、completion pattern、失败案例库 |
| 轨迹日志 | task event log、tool call trace、worker result、judgment record |
| 评测闭环 | eval scenarios、baseline case catalog、experiment run |

当前项目已经有这些雏形，所以更适合把 AHE 当作工程化方向，而不是另起炉灶。

## 3. 可落地的工程原则

### 3.1 不优先堆 Prompt，优先沉淀结构化组件

论文里单独替换系统 Prompt 反而掉分，而长期记忆、工具、中间件带来正收益。

因此本项目后续遇到失败案例时，不应默认把解决方案追加到大段提示词里，而应优先判断它属于哪类结构化修复：

- 工具能力不足：补工具、补参数校验、补输出结构。
- 工具调用误用：补中间件约束、调用前检查、重复调用守卫。
- 路由错误：补 capability/readiness/routing memory。
- 上下文丢失：补 ResumePacket / HandoffPacket / checkpoint 字段。
- 结果不可验证：补 evaluation/judgment schema。
- 反复踩坑：补长期记忆或失败案例库。

### 3.2 每次 Harness 修改都要带“变更契约”

AHE 的一个重点是，演进 Agent 修改组件时必须声明：

- 这次改动预期修复哪些任务。
- 这次改动可能导致哪些回归。
- 修改涉及哪些 Harness 文件或组件。
- 验证它需要跑哪些 case。

本项目可以新增轻量变更清单格式，例如：

```md
## Harness Change Contract

- Change ID:
- Changed components:
- Trigger failure / observation:
- Expected fixes:
- Possible regressions:
- Required eval cases:
- Rollback files:
- Result after eval:
- Keep / rollback decision:
```

短期可以先放在 `docs/` 或 `.tmp/changes/`，后续再结构化进数据库。

### 3.3 失败轨迹要先压缩成“证据语料”，再让 Agent 修改

真实执行轨迹会很长，直接把完整日志交给 Agent 修改代码容易失控。

建议增加一层 Agent Debugger / Trace Summarizer，把原始轨迹压缩成：

- 任务目标。
- 实际执行路径。
- 工具调用序列。
- 关键错误。
- 首个失败点。
- 可疑 Harness 组件。
- 建议修复方向。
- 关联证据引用。

这和当前 `WorkerExecutionResult.evidenceRefs`、`unfinishedItems`、`executionStatus` 等字段方向一致，应该继续强化。

### 3.4 回滚粒度要落到文件或组件

AHE 强调文件粒度回滚。对本项目来说，Harness 组件应尽量模块化，避免所有策略都堆在一个类或一个 Prompt 中。

建议保持以下边界：

- Provider 接入 skeleton 单独文件。
- Worker execution result schema 单独演进。
- Tool-aware executor 单独控制工具链。
- Judgment 模板和执行逻辑分离。
- Learning Memory 写入策略和读取策略分离。
- Eval cases 与生产逻辑分离。

这样后续才能做到“某个演进修改无效时，只回滚对应组件”。

## 4. 对当前编译漂移问题的启发

当前遇到的 `ToolAwareWorkerExecutor` 与 `WorkerExecutionResult` 构造参数不匹配，正好是一个典型 Harness 演进风险：

- Result schema 已经演进。
- 调用点没有同步。
- 编译阶段才暴露漂移。

这类问题后续可以沉淀为一条 Harness 维护规则：

> 任何核心 Result / Packet / Contract schema 增加字段时，必须同步更新所有构造点，并至少跑一次覆盖 tool-aware worker 的编译或测试 case。

这也可以变成 eval case：

- 构造一个最小 tool-aware task。
- 执行 Worker。
- 验证返回结果包含 `executionStatus`、`evidenceRefs`、`unfinishedItems`、`tokenUsage`、`durationMs`、`metadata`。

## 5. 建议新增的项目文档/机制

### 5.1 `docs/HARNESS_EVOLUTION.md`

也就是本文，作为 Harness 自演进方向的总纲。

### 5.2 `docs/HARNESS_CHANGE_CONTRACT.md`

定义每次 Harness 修改必须填写的契约字段。

### 5.3 `docs/TRACE_DEBUGGING_SCHEMA.md`

定义执行轨迹压缩后的结构化格式，支撑后续自动复盘。

### 5.4 `docs/HARNESS_EVAL_MATRIX.md`

把现有 eval scenarios 扩展为按组件归因的矩阵：

- Provider 接入类。
- Tool-aware execution 类。
- Routing 类。
- Resume/Handoff 类。
- Memory consolidation 类。
- Judgment/completion 类。
- Regression guard 类。

## 6. 短期落地优先级

建议按低风险顺序推进：

1. 修复当前 `WorkerExecutionResult` 构造点漂移，保证编译恢复。
2. 给这次修复补一份最小 Harness Change Contract。
3. 在 eval scenarios 中增加一个 tool-aware worker result schema guard case。
4. 后续每次修改 Provider、Worker、Tool、Memory、Judgment 相关代码时，都写明预期修复和潜在回归。
5. 等评测链稳定后，再考虑让 Agent 自动生成修复建议，而不是一开始就让它自动改代码。

## 7. 一句话方向

Agent Cloud Harness 的下一阶段目标，可以从“能调度 Agent 的控制平面”，升级为“能观察、评估并安全演进自身 Harness 组件的控制平面”。
