# Dialogue 页面呈现与执行过程改进方案

> 基于对 `session_8bc98b670d1c4be2`（38 条消息、11 个 artifact、3 个任务、多轮迭代）的实际页面分析。

---

## 1. 当前页面问题

| # | 问题 | 影响 |
|---|------|------|
| **P1** | 消息列表随时间线性膨胀 —— 38 条消息无折叠、无分组 | 滚动疲劳，找不到最新结果 |
| **P2** | 多任务 artifact 混在一起 —— `task_a6c` 和 `task_2511` 的产物无视觉区分 | 搞不清哪个产物属于哪个任务 |
| **P3** | message-summary 只有 1 个 section —— 没有按任务/轮次分组摘要 | 缺乏"执行过程概览" |
| **P4** | worker_round 结束后的 artifact 卡片展示产出预览但缺少"执行过程"中间态 | 8.6 分钟的 codex 执行在 UI 上不可见 |
| **P5** | details 面板的"迭代链"只有名称没有展开内容 | 任务编排过程可观测性不足 |

## 2. 目标体验

```
┌─ Sidebar ───┬─ Workspace ───────────────────────┬─ Details ──┐
│ Threads     │                                    │            │
│             │ ═══ Task: front [2/2] ═══          │ 执行过程    │
│ session_1   │                                    │            │
│ session_2 ● │  Round 1 · deepseek · 12s ──────── │ 摘要 标签   │
│             │   └ "消息被截断" (488 chars)        │            │
│             │                                    │ 按需看轨迹  │
│             │  Round 2 · deepseek · 14s ──────── │            │
│             │   └ "消息可能被截断" (617 chars)    │ 展开 worker │
│             │                                    │ 输出预览    │
│             │  Round 3 · deepseek · 14s ──────── │            │
│             │   └ "消息被截断" (506 chars)        │            │
│             │                                    │            │
│             │  ⚡ Round 4 · codex · 8.6min ───── │            │
│             │   └ ✅ 3.1 MB 产出                  │ ● 代码分析  │
│             │     📁 搜索了 AccountSelectModal    │   视频播放器│
│             │     📁 定位了 VideoCameraOutlined   │   截图功能  │
│             │     📁 对比了新旧项目实现           │ ● 入口定位  │
│             │     ⏸ 等待确认继续                  │   front目录 │
│             │                                    │            │
│             │  [展开完整输出] [继续推进]           │            │
│             │                                    │            │
│ 新建 thread │                                    │            │
└─────────────┴────────────────────────────────────┴────────────┘
```

## 3. 六项改进设计

### 🔴 P1 — 任务时间线折叠：按 task 分组消息

**当前**：所有 38 条消息平铺，包括 `session_receipt`、`task_receipt`、`task_brief`、`worker_round`、`task_progress`。

**改进**：在 `renderMessages()` 之后插入一个任务分组层，将同一个 task 的消息折叠为一个时间线条目。

```
DOM 结构：
  message-summary（顶层摘要，保持不变）
  
  timeline-group[data-task-id="task_a6c..."]
    ├─ group-header: "Task: sobey-m... [1/2]" · 4 rounds · active
    ├─ [collapsed]: 显示最新 worker_round 的输出预览（200 chars）
    └─ [expanded]: 逐个渲染该 task 的所有消息卡
  
  timeline-group[data-task-id="task_2511..."]  
    ├─ group-header: "Task: front [2/2]" · 4 rounds · waiting_human · 8.6m codex
    ├─ round-summary: Round 3 · deepseek · 14s ─ "消息被截断"
    ├─ round-summary: Round 4 · codex · 8.6min ─ ✅ 3.1 MB "对比旧项目..."
    └─ [expanded]: 展开对应的 worker_round 消息
```

**实现**：
- 新增 `timeline-group-plan.js` — 按 task 分组，每组一个 `details` 元素
- 每组默认折叠"老"消息（只展示最新 artifact 预览），可展开
- 当前选中的 task 自动展开

### 🔴 P2 — Artifact 时间线：执行过程可观测

**当前**：`worker_round` 执行期间前端无中间态反馈，11 分钟的 codex 任务对用户来说是"空白等待"。

**改进**：

```
后端：在执行期间，worker 的子进程 stdout 每一行都实时写入 event
前端：轮询 /api/v1/tasks/{id}/events 每 3s 一次，显示活跃状态

Round 4 · codex · 执行中 ⏳  8m 12s elapsed
  ├─ tool: shell_command("rg VideoCamera...")  ← 实时事件
  ├─ tool: read_file("AccountSelectModal/...") 
  └─ tool: shell_command("rg screenshot...")
```

**实现**：
- `TaskService` 执行期间写入 `tool_invocations` 时，同时写一条 `execution_event`（不需要等待完成）
- 前端 `watch` 模式：选中 task 且有 worker_round 执行中 → 每 3s 刷新 events 列表 → 更新 DOM

### 🟡 P3 — artifact 卡片加"执行轮次"标签

**当前**：artifact 卡片只显示 "Worker Output" + 摘要。

**改进**：添加轮次编号 + worker 名称：

```
┌─ Round 4 · codex · 8.6min · 3.1 MB ──────────┐
│ 我会在两个工作区里对比旧项目的视频播放器实现...  │
│ src/components/AccountSelectModal/index.tsx...  │
│ [展开 3.1 MB 完整输出]                          │
└────────────────────────────────────────────────┘
```

### 🟡 P4 — 消息筛选器默认值优化

**当前**：`worker_round` 消息默认全部展示，包括 3 轮失败的 deepseek 输出。

**改进**：增加筛选按钮 "只看最新轮次" / "只看 codex" / "隐藏重复消息"。对连续相同的失败消息（如 deepseek 3 次返回"被截断"），自动合并为一个带计数徽标的折叠消息。

### 🟢 P5 — details 面板"迭代链"展开

**当前**：`#chainContext` 显示 "3 tasks"，但内容就是任务标题列表。

**改进**：将 chain 渲染为迷你时间线：

```
迭代链
  ┌─ task_a6c [1/2] · 2 rounds · active
  ├─ task_2511 [2/2] · 4 rounds · waiting_human ●
  └─ parent task · session_8bc9...
```

### 🟢 P6 — composer 区域精简

**当前**：底部 composer 占 295px（表单 + 选项），在消息流已经挤满时显得浪费空间。

**改进**：非编辑模式下只保留消息输入框（1 行），选项折叠在 "..." 菜单。

---

## 4. 实施建议

| 优先级 | 改动 | 文件 | 工时 |
|:---:|---|------|:---:|
| **P1** | 任务时间线折叠 | 新 `timeline-group-plan.js` + `app.js` | 2-3h |
| **P2** | 执行中实时事件 | `TaskService.java` + `app.js` 轮询 | 3-4h |
| **P3** | artifact 轮次标签 | `app.js` `renderArtifactCard` | 0.5h |
| **P4** | 智能消息筛选 | `app.js` + `app.css` | 1h |
| **P5** | 迭代链时间线 | `chain-context-plan.js` | 1h |
| **P6** | composer 精简 | `app.css` | 0.5h |

**推荐顺序**：先做 P3（artifact 标签），再做 P1（折叠分组）——前者立即改善当前页面，后者解决根本的结构问题。
