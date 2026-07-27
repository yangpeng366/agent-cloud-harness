# Provider 推动 + 边界守护 - 任务理解方案

> 状态：方案设计（未落代码）。主题归属：provider。
> 核心理念：理解、定位、执行都下放给执行 agent（codex 本身就能理解任务）；harness 只提供仓库清单 + 安全边界，不再前置预判。

## 1. 背景与根因

session_4b63c81807094751 / task_eee02813bbe74049（articleeditor 导出 Word 标题缺失加开关）卡 human_gate。根因不是超时：

- intent 含"添加个开关"，但硬编码动作词表没有"添加/导出/下载" -> task_type 未提升 coding。
- 无 workspace_root -> codex cwd 静默回退 user.dir = harness 自己的仓库。
- codex 在错误仓库读 harness 的 STATE.md/events.jsonl，烧 2.25M tokens / 906s，被 turn_max_duration 砍成 partial_timeout -> RuntimeException -> retry 0ms 崩 -> human_gate。

一句话：harness 用关键词猜任务、用回退填 cwd，两处都猜错，且 codex 本来能自己理解却被剥夺了定位权。

## 2. 硬推断点（现状，待收缩）

| # | 位置 | 推断 | 失效 |
|---|------|------|------|
| H1 | TaskTypeHeuristics | coding? 动作词正则+项目名 | 漏"添加/导出"；项目名硬编码 |
| H2 | WorkerRouter.expectsWorkspaceMutation | 需写入? 动作词表 | 本次直接漏"添加/导出/下载/开关" |
| H3 | WorkerRouter.normalizeTaskTypeForRouting | continuation->coding | 依赖 H2 |
| H4 | ControlNodeGraph 1092/4430/3750 | 写入意图/coding候选/错误分类 | 与 H1/H2 重复 |
| H5 | ToolAwareWorkerExecutor 2095/3555 | full-stack/写入意图 | 又一份重复表 |
| H6 | ProviderTaskContractNormalizer | workspace_root? Windows路径正则+\gitall\ | API 路径 /articleeditor/ 不命中 |
| H7 | CodexAppServerWorkerExecutor.resolveWorkingDirectory | codex cwd | 无 workspace 时静默回退 harness 仓库，无边界 |

本质：语义判断用字面匹配必漏覆盖；项目名硬编码不可移植；多份词表重复漂移；H7 静默回退最危险。

## 3. 方向：provider 推动 + 边界守护

不再让 harness 前置 LLM 预判 task_type/workspace（那是多一次 LLM 往返、且和执行 agent 两次理解可能不一致）。改为：

**codex 收到任务原文 + 仓库清单，自己理解、自己定位、自己执行；harness 只守边界。**

三个动作：

### 动作一：仓库清单注入 prompt（根治 cwd 错误）

- harness-config.yml 配 `workspace-aliases`：`articleeditor: D:\gitAll\articleeditor` 等。部署环境事实进配置不进代码。
- prompt 的 Active Context 增加一段「可用工作区」：把 alias registry 列出来（名称->路径）。
- codex 自己理解"这是 articleeditor 的 exportWordSingle"后，自己 cd 到对应仓库工作。
- 删掉当前 prompt 那句"Use the provided local workspaces"的空话（当前实际没提供任何路径）。

### 动作二：cwd 不再静默回退 harness 仓库（止血）

- H7 改：无显式 workspace_root 时，cwd 不回退 user.dir（harness 仓库）。
- 默认回退到一个中性目录（如仓库清单的公共父目录 D:\gitAll），并在 prompt 明确"未指定目标仓库，请从清单自行定位"。
- codex 回报无法定位 / 试图写非清单仓库时，再 escalate human_gate。
- 这样即使没完全配齐 alias，也不会让 codex 误操作 harness 自身仓库。

### 动作三：prompt 边界提示（防误读 harness 文件）

- prompt 加一行：`目标仓库: <codex 自行从清单选定>；仅在目标仓库内工作，不要读写 harness 自身仓库的 STATE.md / docs / 源码`。
- codex 自主决定调研顺序与工具，harness 不干预。

## 4. 不做的事（刻意收缩）

- 不加 LlmTaskUnderstandingService 前置 LLM 判断层：codex 本身就是理解任务的 agent，多一层预判是冗余往返。
- 不重构 task_type 体系：continuation 路由 fallback 到 openclaw-native 的问题单独小修（continuation 也优先 codex），不在本方案主线。
- 不做 intent hash 缓存 / confidence 评估：随前置 LLM 层一起砍掉。
- H1/H2/H4/H5 关键词不急于收敛移除：降为兜底即可，本方案主线不依赖它们（codex 自主理解后，关键词推断对结果不再有决定性影响）。

## 5. 落地优先级

| 优先级 | 动作 | 目的 |
|--------|------|------|
| P0 | 动作一+二：alias registry 注入 prompt + H7 不回退 harness 仓库 | 根治+止血跑错仓库 |
| P0 | 动作三：prompt 边界提示 | 防 codex 误读 harness 文件烧 token |
| P1 | continuation 路由 fallback 单独修（优先 codex） | 消除 task_type 误判的下游影响 |
| 可选 | token guardrail（codex 读大文件/单轮 token 上限） | 防再次烧 2.25M token，独立防护 |

## 6. 风险与回退

- codex 不按清单定位、仍乱跑：prompt 边界提示 + 中性 cwd 兜底；极端情况 escalate。比现状（静默跑错仓库）只好不差。
- alias registry 维护：新项目加一行配置，比改 Java 重建轻得多。
- 回退：本方案不删现有规则推断，只在其之上加仓库清单+边界；规则仍可作兜底，不引入新硬故障。

## 7. 与决策关系

- 更新 DECISIONS 2026-07-22 口径：subgoal 状态迁移仍规则优先；任务理解/定位下放给执行 agent，harness 只守边界。
- 新增决策（待 maintainer 确认）：codex cwd 禁止静默回退 harness 仓库；目标仓库由 agent 从可配 alias registry 自主选定。

## 8. 验证入口（实现后补）

- 复跑本案例等价 intent：期望 codex 自主 cd 到 D:\gitAll\articleeditor，不再读 harness STATE.md。
- 新增测试：H7 无 workspace 时不回退 user.dir；prompt 含仓库清单。