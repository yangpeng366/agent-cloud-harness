# Glossary

> 术语参考表。本文为只读导航词表，不定义新行为；所有释义均与 `docs/ARCHITECTURE.md` 对齐。
> 创建：2026-08-14（auto-patrol R231）。如与源码或 ARCHITECTURE 冲突，以它们为准。

## 用途

Agent Cloud Harness 的文档与代码中使用了大量项目专有术语（continuity、mounted context、packet、judgment 等）。
本表把这些术语收敛成一张可检索的速查表，降低新贡献者与外部读者的认知负担，服务「被他人理解与复用」的开源就绪目标。

## 1. 核心定位

- **Agent Cloud Harness**：面向多轮、长时程、可恢复任务执行的轻量 runtime harness；单机形态，非分布式 control plane。
- **continuity-first runtime substrate**：项目最准确的自我定位——核心职责是让任务在时间维度上保持连续、可观察、可恢复、可移交、可再进入，而非只优化单次回答。
- **task identity over time**：设计主轴之一，强调任务身份跨轮存在，优于单轮回答质量。

## 2. 运行时与连续性

- **TaskRuntimeContext**：任务级运行时上下文，承载一轮执行所需的状态；见 `runtime/` 层与 §6.2。
- **TaskRuntimeContextBuilder**：组装 TaskRuntimeContext 的构建器；被列为需控制复杂度的代码面之一。
- **ActiveContext**：上游综合面，仍有价值，但定位收敛为 compatibility projection / synthesis layer，不再是最终抽象。
- **execution working set**：下一层目标抽象（如 `ExecutionWorkingSet`），从 TaskRuntimeContext 投影出更紧凑、供 execution 与 judgment 共享消费的结构化工作面。
- **runtime_cognition_surface / runtime_cognition_timeline**：暴露 route / execution / judgment 认知边界与漂移的观测面，并把 pause/resume/checkpoint/handoff 收口到同一条 continuity timeline。

## 3. Mounted Context（Phase 2 working-memory spine）

- **mounted context**：当前最重要的新 seam，给 runtime 一个 task-local working-memory surface，把 context engineering 从经验做法推进成 runtime contract。
- **MountedContextView**：mounted working-memory view 的核心模型，挂载于 `TaskRuntimeContext.mountedContextView`。
- **ContextViewBuilder**：构建 mounted view 的组装器。
- **MountedContextPromptRenderer**：把 mounted view 渲染进 prompt 的渲染器。
- **PromptRenderingMode**：prompt 渲染模式 seam，控制渲染策略。

## 4. 执行、工具与判断

- **worker / worker execution**：执行层，负责一轮执行、工具调用与结果结构化；默认与 tool-aware 两种执行器分流。
- **DefaultWorkerExecutor / ToolAwareWorkerExecutor**：两个执行器实现；mounted seam 已进入二者。
- **WorkerExecutionResult / WorkerExecutionEnvelope**：执行结果与执行信封（含执行元数据与 trace）。
- **execution envelope / trace**：把执行过程包成可审计的信封，沉淀 tool trace、artifact、decision 等证据。
- **judgment**：判断层，prompt-based 驱动 continue / wait / checkpoint / handoff / done，并能解释为什么继续/暂停/结束。
- **PromptBasedJudgmentService / JudgmentContext**：judgment 层的核心服务与上下文。
- **ToolRegistry / ToolPolicy / ToolResult**：工具层；受 `ToolPolicy` 约束的受控本地工具（含文件工具与命令工具），`powershell/cmd` 仅 Windows 宿主可用。

## 5. 持久化与 Packet（continuity 资产）

- **ResumePacket**：机器可读的恢复包，承载 resumability。
- **Checkpoint**：检查点，承载可恢复的中断边界。
- **HandoffPacket（方向）**：结构化移交包，承载跨会话/跨执行器的移交语义。
- **Task / Decision / Artifact / ToolInvocationRecord**：模型层 DTO，分别承载任务、决策、工件、工具调用记录。

## 6. 应用编排与前端

- **ControlNodeGraph**：控制动作编排图；列为需重点控制复杂度的代码面（§10.1）。
- **TaskService / SessionService / ExperimentRunService**：应用层服务，管理任务/会话生命周期、实验与学习记忆。
- **Web Console / Dialogue**：内置前端（`/console/` 与 `/dialogue/`），Vanilla JS + CSS。

## 7. 技术栈要点

- 语言：Java 21（`--enable-preview`）；Maven 单模块。
- HTTP：JDK `HttpServer` + 虚拟线程，手写 `HttpHandler` 路由，不引入 Spring/Jersey。
- 数据：SQLite（本地文件）+ Jdbi SQL Object（注解式 SQL）+ HikariCP。
- JSON：Jackson，统一 `snake_case` 输出。

## 8. 相关文档

- 架构权威：`docs/ARCHITECTURE.md`
- 规格：`docs/SPEC.md`
- 文档治理：`docs/DOCS_GOVERNANCE.md`
- 发布就绪：`docs/release/README.md`

