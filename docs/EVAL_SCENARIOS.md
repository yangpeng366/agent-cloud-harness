# EVAL_SCENARIOS

## 1. 目的

本文档将 `agent-cloud-harness` 的核心控制回路拆成可执行、可断言、可复跑的评测场景。

目标不是做开放域“智能排行榜”，而是验证以下系统性质：

- continuity 是否稳定
- routing 是否正确
- state machine 是否一致
- checkpoint / handoff / human_gate 是否可恢复、可审计
- judgment 是否只在必要处引入弹性，而不破坏控制层可预测性

---

## 2. 测试设计原则

### 2.1 评测对象

评测对象是完整 harness 行为，而不是裸模型。

测试覆盖：

- HTTP handler
- Task / Session / Event / Checkpoint 持久化
- ControlNodeGraph 节点迁移
- Worker routing
- Worker execution 输出
- Judgment 决策
- Pause / Resume / Handoff / Escalate

### 2.2 断言优先级

优先验证：

1. 状态变化是否正确
2. 是否留下结构化 trace
3. 是否能恢复执行
4. 是否生成最小必要 packet
5. 是否触发正确的人机边界

不优先验证：

- 文案是否华丽
- 模型回答是否“像真人”
- 开放域能力上限

### 2.3 场景格式

每个场景按以下结构描述：

- 场景编号
- 目标
- 初始条件
- 输入
- 预期路径
- 核心断言
- 失败信号
- 后续扩展

---

## 3. 场景总览

| 编号 | 场景名 | 目标能力 |
|------|--------|----------|
| T01 | 创建任务并自动进入 worker | session 自动补全、初始路由 |
| T02 | worker 一轮完成并结束 | 完成判断、done 迁移 |
| T03 | worker 输出不足并继续 | continue judgment、next_step 更新 |
| T04 | pause 后 resume | continuity、checkpoint 恢复 |
| T05 | handoff 到其他 worker | packet 质量、跨 worker 连续性 |
| T06 | 升级到 human_gate | 人机边界正确性 |
| T07 | 路由错误后的纠偏 | 纠偏与回退能力 |
| T08 | 多 checkpoint 长链连续性 | 压缩历史、保持可恢复 |
| T09 | 人工恢复后继续执行 | gate 退出后的状态闭环 |
| T10 | 结构化审计回放 | trace 与事件可重建 |

---

## 4. 详细场景

## T01. 创建任务并自动进入 worker

### 目标

验证新任务从创建到进入初始 worker 的基本闭环。

### 初始条件

- 数据库为空或处于可控初始状态
- skill / worker registry 已初始化
- 默认 worker 可用

### 输入

`POST /api/v1/tasks`

示例输入：

```json
{
  "title": "整理 phase-2 实施建议",
  "goal": "输出下一阶段改造要点",
  "session_id": null
}
```

### 预期路径

```text
create task
  -> auto create session
  -> write task/event
  -> enter intake/scheduler
  -> select default worker
  -> write route decision
```

### 核心断言

- 自动创建 session
- task 被持久化
- task 初始 `control_node` 合法
- event 表中出现 task_created / routed 类事件
- 已记录当前选中的 worker

### 失败信号

- task 已创建但没有 session
- task 卡在 intake 且无后续事件
- worker 为空但未报错
- 没有 route reason

### 后续扩展

- 补多 worker 条件下的路由比较测试

---

## T02. worker 一轮完成并结束

### 目标

验证一轮执行就可收敛的任务，是否能正确进入 `done`。

### 初始条件

- 存在可执行 worker
- judgment 能返回完成信号

### 输入

创建一个简单任务，例如：

```json
{
  "title": "输出 hello world 摘要",
  "goal": "生成一句简短结论"
}
```

### 预期路径

```text
task create
  -> route worker
  -> execute once
  -> completion judgment = done
  -> persist final event/checkpoint
  -> status = done
```

### 核心断言

- worker 输出被写入 event 或 artifact
- completion judgment 存在结构化结果
- task.status = done
- task.control_node = done 或等价终态
- 最终状态无悬空 next_step

### 失败信号

- 明显完成却继续循环
- status=done 但没有 final trace
- done 后仍可再次执行且无保护

### 后续扩展

- 验证 done 场景下 final packet 是否生成

---

## T03. worker 输出不足并继续

### 目标

验证系统不会过早结束，而是能根据 judgment 继续下一轮。

### 初始条件

- judgment 支持 `continue`
- task 可持有 next_step

### 输入

一个信息不足、需要继续澄清或继续执行的任务。

### 预期路径

```text
execute worker
  -> output partial
  -> completion judgment = partially_done / continue
  -> update next_step
  -> return scheduler or continue loop
```

### 核心断言

- judgment.action = continue 或等价值
- task 未进入 done
- next_step 被刷新
- 事件中记录“为什么继续”

### 失败信号

- partial output 被误判为 done
- 进入 continue 但 next_step 为空
- 无理由继续导致后续执行漂移

### 后续扩展

- 增加 low confidence 与 ambiguous result 的测试分支

---

## T04. pause 后 resume

### 目标

验证中断恢复链条是否成立。

### 初始条件

- 存在进行中的任务
- checkpoint 功能开启

### 输入

1. 创建一个需要多轮执行的任务
2. 在中间节点调用 pause
3. 随后调用 resume

### 预期路径

```text
running
  -> pause
  -> checkpoint snapshot generated
  -> status = paused
  -> resume
  -> rebuild active context
  -> continue from expected node
```

### 核心断言

- pause 后任务进入 paused
- checkpoint 可读且包含最小恢复信息
- resume 后不会从头开始
- resume 后 control_node 合法
- recent artifacts / next_step / route context 仍可用

### 失败信号

- pause 只改状态，不生成 checkpoint
- resume 丢失 next_step
- resume 回到错误 worker 或错误节点
- 恢复只能依赖全量历史日志

### 后续扩展

- 增加“长时间后恢复”的场景

---

## T05. handoff 到其他 worker

### 目标

验证跨 worker 连续性与 handoff packet 质量。

### 初始条件

- 至少两个 worker 可选
- handoff 路径已实现

### 输入

创建一个更适合另一个 worker 处理的任务，或在执行中人为触发 handoff。

### 预期路径

```text
worker A execute
  -> insufficient fit
  -> handoff decision
  -> generate packet
  -> route worker B
  -> worker B continue with packet
```

### 核心断言

- handoff 决策存在结构化 reason
- packet 包含目标 worker 可继续的最小信息
- worker B 不需要回读全量历史即可继续
- handoff 前后 task identity 不变

### 失败信号

- handoff 只有一句自然语言，没有结构化 packet
- 换 worker 后 next_step 丢失
- worker B 需要重新推理整个任务背景

### 后续扩展

- 增加多次 handoff 链式场景

---

## T06. 升级到 human_gate

### 目标

验证系统在不确定、高风险、缺关键信息时会正确升级给人。

### 初始条件

- judgment 支持 escalate / wait_human
- human_gate 节点存在

### 输入

例如：

- 存在高风险操作
- 用户意图不明确
- 缺关键外部确认

### 预期路径

```text
worker/judgment
  -> detect ambiguity or risk
  -> escalate
  -> status = waiting_human
  -> preserve checkpoint + pending question
```

### 核心断言

- 不确定场景不会盲目继续
- task 进入 waiting_human 或等价状态
- pending question 明确
- checkpoint 保留当前面

### 失败信号

- 系统在高不确定性下强行继续
- 进入人工等待态但未说明等待什么
- 人工回来后无法恢复现场

### 后续扩展

- 测试不同风险级别阈值

---

## T07. 路由错误后的纠偏

### 目标

验证 scheduler 错判后，系统能否通过 judgment/handoff 修正路径。

### 初始条件

- 人为构造一条容易误路由的任务
- 至少两个 worker 具备不同擅长域

### 输入

让任务先落到不合适 worker。

### 预期路径

```text
misroute
  -> worker output indicates mismatch
  -> judgment/handoff detect mismatch
  -> reroute to correct worker or human
```

### 核心断言

- 错误路由不会无限循环
- mismatch 能被记录
- 纠偏后任务继续推进

### 失败信号

- 同一错误 worker 重复执行
- mismatch 被吞掉
- route correction 后上下文断裂

### 后续扩展

- 增加 route confidence 字段测试

---

## T08. 多 checkpoint 长链连续性

### 目标

验证任务经历多轮执行后，系统仍能保持可恢复性，而不依赖全量对话历史。

### 初始条件

- 任务可运行多轮
- checkpoint 支持多版本

### 输入

让任务经历至少 3 次：

- continue
- pause/resume
- handoff 或再次 continue

### 预期路径

```text
run -> checkpoint1
    -> continue -> checkpoint2
    -> pause/resume -> checkpoint3
    -> continue/handoff
```

### 核心断言

- 最新 packet 能概括当前工作面
- 历史增长后恢复成本不线性爆炸
- 旧 checkpoint 与新 checkpoint 不冲突

### 失败信号

- checkpoint 只会越写越长
- 最新 packet 缺关键决策
- 恢复必须依赖全量 event replay

### 后续扩展

- 加 token budget / packet budget 检查

---

## T09. 人工恢复后继续执行

### 目标

验证 human_gate 不是终点，而是可以形成闭环继续执行。

### 初始条件

- 已存在 waiting_human 任务

### 输入

人工追加说明、批准、或补充约束后恢复。

### 预期路径

```text
waiting_human
  -> receive human input
  -> attach input to context
  -> reroute or continue
  -> task progresses
```

### 核心断言

- 人工输入被纳入 context
- task 退出 waiting_human
- next_step 被更新
- 执行链条继续，不发生上下文重置

### 失败信号

- human response 只记录消息，不影响任务状态
- 恢复后上下文断裂
- 系统忽略人工新增约束

---

## T10. 结构化审计回放

### 目标

验证任务全程是否具备可审计、可重建能力。

### 初始条件

- 任务已完整走完至少一条非平凡路径

### 输入

读取任务相关：

- task record
- events
- checkpoints
- handoff packet
- final status

### 预期路径

```text
load trace
  -> reconstruct route path
  -> reconstruct key decisions
  -> reconstruct current/final state
```

### 核心断言

- 能回答“任务怎么走到这里”
- 能回答“为什么换 worker / 为什么暂停 / 为什么升级给人”
- 审计者不需要阅读全部原始文本日志

### 失败信号

- 决策只有最终结果，没有原因
- event 名称存在，但 payload 空洞
- checkpoint 与最终状态对不上

---

## 5. 建议的断言层次

为减少实现初期成本，建议分三层推进。

### L1. 状态层断言

只看：

- status
- control_node
- worker
- session_id
- next_step

适合最初 smoke test。

### L2. 结构化对象层断言

再看：

- checkpoint payload
- handoff packet
- judgment decision
- execution payload

适合 phase-2 主测试。

### L3. 审计与恢复层断言

最后看：

- 是否可独立恢复
- 是否可独立审计
- packet 是否足够替代长历史

适合 continuity-first 核心验收。

---

## 6. 推荐落地顺序

### 第一批

- T01
- T02
- T03
- T04

先稳住最小闭环和 pause/resume。

### 第二批

- T05
- T06
- T07

再稳住 handoff、人工边界、纠偏。

### 第三批

- T08
- T09
- T10

最后做长链连续性和审计回放。

---

## 7. 与后续文档的关系

本文档定义“测什么”。

后续需要配套：

- `CHECKPOINT_PACKET_SPEC.md`
  - 解决“packet 长什么样”
- `WORKER_EXECUTION_PROTOCOL.md`（可选）
  - 解决“worker 输出如何结构化”
- `JUDGMENT_DECISION_SPEC.md`（可选）
  - 解决“decision 输出如何断言”

---

## 8. 结论

`agent-cloud-harness` 的评测重点应该是：

- 它是否能稳定保持 continuity
- 它是否能把任务送到合适 worker
- 它是否能在中断、交接、升级给人之后继续工作
- 它是否能留下可审计、可恢复的结构化痕迹

如果这些测试能稳定通过，这个项目才算具备真正的 control-plane 价值，而不是停留在“有一个会跑的 demo loop”。
