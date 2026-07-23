# Dialogue 页面交互优化方案

> 基于 puppeteer 实测截图 + DOM 分析。当前页面: 55 sessions, 38 cards, thread-panel height=2014px 嵌入 message-panel__body 内。

---

## 1. 当前布局结构

```
workspace (884px = 100vh - 16px)
├── workspace__header (96px)
├── message-panel (flex: 1 → 493px)
│   ├── message-panel__filters (hidden, 0px)
│   └── message-panel__body (493px, overflow-y:auto, justify-content:flex-end)
│       ├── thread-drawer (23px collapsed / 2014px open)    ← 问题
│       ├── message-summary (fixed)
│       └── message-list (38 条消息)
└── composer-panel (295px)
```

## 2. 当前问题

| # | 问题 | 现象 |
|---|------|------|
| **A** | **thread-drawer 挤占消息流** — 打开时 2014px 占满 message-panel__body（493px），消息完全看不见 | 用户说"好像有点固定，上面内容没展示完整" |
| **B** | **composer 过高** — 295px 占 workspace 的 33%，但大部分时候只用消息输入框 | 页面浪费在表单而不是内容上 |
| **C** | **message-panel__body 使用 flex-end** — 消息永远从底部开始，打开 thread-drawer 时连消息都看不到 | 消息流被推出可视区域 |
| **D** | **38 条消息无分组折叠** — 所有消息平铺在一个滚动容器里 | 之前诊断文档已提过 |
| **E** | **details 面板右侧 ~292px** — 当关闭 details 时 workspace 得到更多空间，但默认打开 | 浪费水平空间 |

## 3. 布局重设计方案

### 目标布局

```
┌─ Sidebar ───┬─ Workspace ────────────────────┬─ Details ──┐
│             │  [header: 56px compact]         │            │
│  Threads     │  ───────────────────────────────│  选中任务   │
│             │                                 │  详情折叠   │
│  session-1  │  ┌─ task tabs ─────────────────┐│            │
│  session-2 ●│  │ [1/2] [2/2●] [parent]      ││  执行摘要   │
│             │  └─────────────────────────────┘│            │
│  session-3  │                                 │  产物预览   │
│             │  ═══ 当前 task: front [2/2] ═══ │            │
│             │  ▸ Round 4 · codex · 8.6min     │            │
│             │    📄 对比新旧项目视频播放器...   │            │
│             │    📄 import React, { useEffect  │            │
│             │    [展开 4.8 MB 完整输出]         │            │
│             │                                 │            │
│             │  ▸ Round 3 · deepseek · 14s     │            │
│             │  ▸ Round 2 · deepseek · 14s     │            │
│             │                                 │            │
│  ────────── │ ───────────────────────────────│            │
│  新建 thread│  [composer: 1行输入框 + ▸ 选项]  │            │
└─────────────┴─────────────────────────────────┴────────────┘
```

### 四项改动

### 🔴 A — thread-drawer 从 message-panel__body 移到 workspace 级别

**当前**：`thread-drawer` 是 `message-panel__body` 的第一个子元素，打开时把消息推出视图。

**改进**：将 thread-drawer 移为 `message-panel` 的直接子元素（在 `__filters` 和 `__body` 之间），让 message-panel 的 flex 容器自然给它分配顶部空间。

```css
/* message-panel 内部布局 */
.message-panel {
    display: flex;
    flex-direction: column;
}
/* thread-drawer 在 top: max-height 40% 且 overflow-y: auto */
.thread-drawer[open] {
    max-height: 40%;
    overflow-y: auto;
}
/* message-panel__body 始终可见 */
```

### 🔴 B — composer 精简为单行模式

**当前**：295px 高度包含完整表单（标题、类型、优先级、worker、goal、auto-start...）。

**改进**：默认折叠到 48px（单行输入框 + 发送按钮），点击 "▸ 选项" 展开完整表单。

```css
.composer-panel {
    min-height: 48px;
    max-height: fit-content;
    transition: max-height 0.2s;
}
.composer-panel--expanded {
    max-height: 295px;
}
```

### 🟡 C — header 紧凑化

**当前**：96px 包含标题、状态栏、subbar。

**改进**：压缩到 56px，把 subbar 合并到同一行。

```css
.workspace__header {
    padding: 6px 10px;
}
.workspace__sessionbar {
    flex-wrap: nowrap;
}
/* 状态栏移到 subbar 位置，用一行展示 */
```

### 🟡 D — message-panel__body 改用 flex-start

```css
.message-panel__body--stream-only {
    justify-content: flex-start;  /* 从 flex-end 改 */
}
.message-panel__body--stream-only .message-stream {
    margin-top: 0;  /* 从 auto 改 */
}
```

## 4. 实施顺序

| 优先级 | 改动 | 文件 | 工时 |
|:---:|---|------|:---:|
| **P0** | Thread-drawer 移出 message-panel__body | `app.js` renderMessages + `app.css` | 30m |
| **P0** | Composer 精简 | `app.css` + `app.js` | 30m |
| **P1** | Header 紧凑 | `app.css` | 15m |
| **P1** | 消息从顶部开始（flex-start） | `app.css` | 5m |
| **P2** | 消息折叠分组 | 新 `timeline-group-plan.js` | 2h |
