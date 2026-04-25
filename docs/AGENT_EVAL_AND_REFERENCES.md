# Agent 评测与外部参考草案

## 1. 目的

本文档用于给 `agent-cloud-harness` 提供一份可落地的外部参考映射，重点不是罗列所有 agent 文章，而是把网上较有代表性的设计意见、评测观点、测试思路，沉淀成适合当前项目的工程约束与测试框架。

目标：

- 为当前 harness 的控制层设计提供外部参照
- 为后续 `routing / checkpoint / handoff / human_gate / pause-resume` 提供评测维度
- 避免只参考“智能程度”而忽略 continuity、审计性、恢复性

---

## 2. 本轮外部浏览摘要

本轮参考重点来自以下公开资料：

1. Anthropic, **Building effective agents**
2. Anthropic, **Raising the bar on SWE-bench Verified with Claude 3.5 Sonnet**
3. LangGraph, **Workflows and agents**
4. Microsoft AutoGen, **Group Chat / Multi-Agent Design Patterns**

这些资料共同提供了四类高价值观点：

- 什么时候应该用 workflow，什么时候才应该上 agent
- agent scaffold 的质量会显著影响最终效果
- persistence / interrupts / memory / orchestration 是生产级 agent 系统的关键能力
- multi-agent 的价值不在“多”，而在角色分工、消息协议、调度规则、终止条件

---

## 3. 关键外部观点

### 3.1 Anthropic: 先追求简单、可组合，而不是复杂框架

外部观点：

- 成功的 agent 实现往往依赖简单、可组合的模式，而不是过重框架
- workflow 适合预定义路径、稳定任务
- agent 适合高不确定性、需要模型动态决策的场景
- 复杂系统会以 latency / cost / debugability 为代价换取更高灵活性

对本项目的启发：

- `agent-cloud-harness` 不应把所有节点都 agent 化
- scheduler、checkpoint、handoff、human_gate 这类控制节点应优先保持确定性
- 只有 worker 内部执行和少数 judgment 点，才适合保留模型驱动弹性
- 控制平面应把“复杂性预算”压在最少节点上

落地原则：

1. 控制节点默认 deterministic first
2. judgment 是局部能力，不是总控替代物
3. 能用规则完成的路由，不要提前升格成开放式 agent 选择

### 3.2 SWE-bench 的真正启发不是分数，而是 scaffold

外部观点：

- SWE-bench 评测的不是裸模型，而是“模型 + scaffold”
- 同一个模型，因为 scaffold 不同，表现会有明显差异
- 可验证结果比表面上看起来聪明更重要
- Verified 子集强调“题目可解、结果可验”

对本项目的启发：

- `agent-cloud-harness` 应重点评测 harness 本身，而不是只评测底层 LLM
- 测试对象应该是完整控制回路：
  - task create
  - route
  - execute
  - checkpoint
  - pause/resume
  - handoff
  - escalate
  - done
- 每个关键节点都需要可验证输出，而不是仅保存自然语言日志

落地原则：

1. 以“整套 scaffolding 行为”作为评测对象
2. 以可断言状态变化代替主观印象打分
3. 优先做可复跑、可对比、可审计的测试集

### 3.3 LangGraph: persistence / interrupts / memory / subgraph 是生产能力

外部观点：

- workflow 与 agent 都需要明确状态流转
- persistence、durable execution、interrupts、memory 是生产系统要素
- routing、orchestrator-worker、evaluator-optimizer 是常见结构模式

对本项目的启发：

- `ControlNodeGraph` 的方向是对的，但需要更明确地把“状态与中断恢复”当一等能力
- checkpoint 不能只是摘要产物，而要成为真正的 continuation boundary
- pause / resume 不应是附属动作，而应是主流程公民
- worker routing、completion judgment、handoff decision 需要形成可测试状态图

落地原则：

1. 节点迁移必须留下结构化 trace
2. interrupt 后恢复必须能重建 active context
3. packet 既是摘要，也是可恢复边界对象

### 3.4 AutoGen: multi-agent 的难点在协议、调度和终止条件

外部观点：

- multi-agent 不只是多角色堆叠，而是消息协议、topic/subscription、speaker selection、termination condition
- group chat / handoff / debate / sequential workflow 都是不同通信模式
- 角色分工必须清晰，否则只会增加成本和噪音

对本项目的启发：

- `agent-cloud-harness` 后续如果扩多 worker / managed agents，关键不在“接入多少模型”，而在：
  - 任务何时切换 worker
  - worker 之间怎样传 packet
  - 谁有权决定终止
  - 谁能触发升级给人
- handoff packet 的协议质量会直接决定系统稳定性

落地原则：

1. 先统一 handoff packet，再扩 worker 数量
2. 先定义 termination，再开放多方协作
3. 先做单 owner 控制流，再做多 agent 协商流

---

## 4. 映射到 agent-cloud-harness 的核心评测维度

结合外部资料，当前项目最值得优先建设的不是“开放式通用 benchmark”，而是以下六类系统评测。

### 4.1 可恢复性

问题：

- 任务在任意节点中断后，是否能靠 checkpoint / packet / recent events 恢复
- 恢复后是否会丢失 next_step、worker intent、artifact context

重点断言：

- resume 后 task 状态一致
- active context 足以继续执行
- 不需要重新人工解释整个历史

### 4.2 路由正确性

问题：

- scheduler 是否把任务路由给合适 worker
- handoff 是否在合理时机发生
- 路由原因是否可解释

重点断言：

- 路由结果与预期 worker 匹配
- 决策 trace 中存在理由
- 误路由后系统能回退或升级

### 4.3 状态一致性

问题：

- tasks / sessions / events / checkpoints / artifacts 之间是否一致
- 单步失败会不会产生半更新状态

重点断言：

- control_node 与 status 对应关系正确
- checkpoint 与当前节点一致
- pause / handoff / escalate 不会留下破碎状态

### 4.4 人机边界正确性

问题：

- 什么时候继续自动跑
- 什么时候需要 human_gate
- 什么时候应该 escalate 而不是瞎继续

重点断言：

- 高风险、不确定、缺关键信息场景会触发人工边界
- 非关键场景不会过度打断
- 人工恢复后流程继续可控

### 4.5 审计性

问题：

- 关键动作是否都能回看
- 决策是否有可审计 reason
- packet / checkpoint 是否能解释“为什么走到这里”

重点断言：

- 每次关键节点迁移有结构化 event
- handoff packet 能独立理解当前面
- 审计视图可还原任务路径

### 4.6 最小 agent 化

问题：

- 是否把本该 deterministic 的部分过度交给模型
- 模型自由度是否过大导致控制层失稳

重点断言：

- control node 迁移优先受规则驱动
- judgment 只在必要点生效
- 不因引入模型而损害可预测性

---

## 5. 推荐的最小测试场景集

以下场景比通用聊天评测更适合当前项目。

### T01. 创建任务并自动进入 worker

验证：

- `POST /tasks` 后自动补 session
- 进入 scheduler
- 选中默认 worker
- 写入 events

### T02. worker 正常完成并进入 done

验证：

- worker 输出被记录
- judgment 识别为完成
- task 进入 done
- 产生最终 checkpoint 或 final event

### T03. worker 输出不足，继续下一轮

验证：

- judgment 返回 continue
- next_step 更新
- control_node 回到 scheduler 或 working loop

### T04. 中途 pause，再恢复

验证：

- pause 后状态冻结
- checkpoint 可读
- resume 后上下文可继续使用

### T05. 需要 handoff 的任务

验证：

- 当前 worker 无法继续时触发 handoff
- packet 包含目标 worker 所需最小上下文
- 新 worker 可直接接续

### T06. 不确定或高风险，触发 human_gate

验证：

- judgment 能识别需要人工确认
- task 进入等待态
- 人工恢复后能继续流程

### T07. 路由错误后的纠偏

验证：

- 首次 worker 选择错误
- 系统能通过 judgment/handoff 修正
- 不会一直困在错误 worker 上

### T08. 多次 checkpoint 后的长期连续性

验证：

- 多轮运行后仍能生成紧凑 continuation packet
- 不依赖全量历史也能继续
- packet 与 recent events 不冲突

---

## 6. 对当前代码阶段的直接建议

结合仓库现状，建议按下面顺序补文档后的实现。

### 6.1 先补结构化评测对象

优先把以下对象稳定下来：

- WorkerExecutionPayload
- ExecutionDecision
- CompletionDecision
- HandoffPacket
- CheckpointSnapshot

原因：

没有稳定对象，外部观点无法真正转成测试。

### 6.2 把 checkpoint 从“摘要”提升为“恢复边界”

这和 LangGraph / continuity-first 方向一致。

最小要求：

- packet 可独立阅读
- packet 能重建 active context
- packet 包含 next_step / pending question / target worker / artifacts summary

### 6.3 把评测从“模型效果”转向“控制层正确性”

最初一版不要追求：

- 通用 agent 榜单
- 开放域智能评分
- 多模型横向竞技

应该优先追求：

- 状态图正确
- 恢复链稳定
- packet 协议稳
- routing / handoff / escalate 可断言

---

## 7. 建议新增的项目文档与目录

建议把测试沉淀成独立文档，而不是散在 roadmap 里。

推荐新增：

```text
docs/
  AGENT_EVAL_AND_REFERENCES.md
  EVAL_SCENARIOS.md
  CHECKPOINT_PACKET_SPEC.md
```

建议分工：

- `AGENT_EVAL_AND_REFERENCES.md`
  - 外部参考
  - 设计原则
  - 评测维度

- `EVAL_SCENARIOS.md`
  - 测试用例
  - 输入
  - 预期状态迁移
  - 断言点

- `CHECKPOINT_PACKET_SPEC.md`
  - packet 字段
  - 预算规则
  - 最小恢复要求

---

## 8. 最终建议

对 `agent-cloud-harness` 而言，当前最有价值的外部参考共识不是“如何让 agent 看起来更聪明”，而是：

1. 先用 workflow 稳住控制层
2. 把 agent 能力放在必要节点，而不是全局泛化
3. 评测对象是 scaffold + state machine，而不是裸模型
4. 把 persistence / interrupt / memory / handoff 当作一等公民
5. multi-agent 的关键在协议和终止条件，不在角色数量

如果按这个方向推进，本项目会更像一个稳定的 continuity-first harness，而不是一个容易发散的 demo agent shell。
