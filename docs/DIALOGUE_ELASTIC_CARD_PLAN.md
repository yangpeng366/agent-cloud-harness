# Dialogue 弹性卡片方案 — Pretext + CSS contain

> **目标**: 消息卡片弹性高度、点击展开完整内容、无需内部滚动条、基于 Pretext 精确测量。
> **前提**: 不推翻现有的 `message-card--expandable` 机制，增量叠加 Pretext 测量 + CSS contain。

---

## 1. 当前架构回顾（已有的基础）

`renderMessageCard()` 已经支持了展开/折叠模式：

```html
<article class="message-card message-card--expandable">
  <div class="message-card__meta">...</div>
  <div class="message-card__collapsed-body">预览 300 字符</div>
  <div class="message-card__full-content">完整内容</div>
  <div class="message-card__expand-indicator">展开完整结果</div>
</article>
```

CSS 通过 `.message-card--expanded` 切换 `.message-card__collapsed-body` 和 `.message-card__full-content` 的 `display`。

**当前问题**：
1. 折叠高度是写死的 `max-height` 估算值，不是基于文本内容精确计算的
2. 展开后大量文本（4.8 MB artifact）直接塞进 DOM，无分块/懒加载
3. 展开一张卡片会触发其他卡片的 reflow

## 2. 目标效果

```
卡片折叠态（默认）              卡片展开态（点击后）
┌────────────────────────┐    ┌────────────────────────┐
│ assistant · worker_round│    │ assistant · worker_round│
│ codex · 8.6min         │    │ codex · 8.6min         │
│                        │    │                        │
│ 我会先对比旧项目…       │ →  │ 我会先对比旧项目…       │
│ import React, { useEff…│    │ import React, { useEff… │
│                        │    │ ... (200 行后)         │
│          [展开完整结果] │    │ ... (500 行后)         │
└────────────────────────┘    │          [收起]        │
                              └────────────────────────┘

高度 = Pretext 算出的 3 行     高度 = Pretext 算出的全文高度
       (~80px 精确值)              (可能是 800px 或 8000px)
```

## 3. 技术方案

### 3.1 三阶段测量

```
阶段 1: 预处理 (prepare)
  input:  消息正文 text, CSS font 声明
  output: prepared handle（缓存 Canvas 测量结果）
  cost:   一次性，可复用

阶段 2: 折叠态高度 (layout3lines)
  input:  prepare handle, 卡片内容宽度, lineHeight
  output: 精确 3 行高度（px）
  cost:   纯算术，亚毫秒

阶段 3: 完整高度 (layout)
  input:  prepare handle, 卡片内容宽度, lineHeight
  output: 全文高度（px）
  cost:   纯算术，亚毫秒
```

### 3.2 CSS contain 隔离

```css
.message-card {
    contain: layout style;        /* 隔离布局：展开不触发兄弟卡 reflow */
}

.message-card__collapsed-body {
    overflow: hidden;             /* 超出 3 行隐藏 */
}

.message-card--expanded .message-card__collapsed-body {
    overflow: visible;            /* 展开后显示全文 */
    max-height: none;
}

.message-card__collapsed-body {
    /* 由 JS 动态设置：Pretext 算出的 3 行精确高度 */
    max-height: var(--card-preview-height, 63px);
}
```

### 3.3 大文本分块（artifact > 5000 字符）

artifact 4.8 MB 不能全塞 DOM。在展开时按需分块：

```
第 1 块: 前 500 行 → 渲染
滚动到底部 ← 自动加载第 2 块
第 2 块: 501-1000 行 → 追加渲染
...
```

用 `IntersectionObserver` 检测"加载更多"标记是否进入可视区。

## 4. 改动点（3 处）

### 4.1 `message-measure.js` — 新增 `predictCollapsedHeight()`

```javascript
/**
 * 预计算折叠态高度（前 3 行 + padding）
 */
export function predictCollapsedHeight(message, opts = {}) {
    const cw = (opts.cardWidth || 780) - 24;
    const fb = opts.fontBody || '14px Inter, system-ui, sans-serif';
    const lh = opts.lineHeight || 21;
    const lines = 3;  // 折叠态显示行数

    const body = message?.content || '';
    const h = prepare(body, fb);
    const { lineCount } = layout(h, cw, lh);

    const previewLines = Math.min(lines, lineCount);
    const meta = 28, pad = 12;
    const strip = (message?.metadata?.execution_status
        || message?.metadata?.selected_worker
        || message?.metadata?.worker_id) ? 36 : 0;

    return Math.max(48, Math.ceil(meta + pad + previewLines * lh + strip));
}
```

### 4.2 `app.js` — 在 `renderMessageCard()` 中注入 CSS 变量

在生成 `.message-card__collapsed-body` 的 div 时，注入 Pretext 计算的高度：

```javascript
// 在 renderMessageCard 中
const previewHeight = predictCollapsedHeight(message, {
    cardWidth: dom.messageList.clientWidth || 780
});
const fullHeight = predictCardHeight(message, { cardWidth: dom.messageList.clientWidth || 780 });
const needsExpand = fullHeight > previewHeight + 20;  // 20px 阈值

return `
    <article class="message-card ${needsExpand ? 'message-card--expandable' : ''}">
        ...
        <div class="message-card__collapsed-body"
             style="--card-preview-height:${previewHeight}px; max-height:${previewHeight}px">
            ${escapeHtml(needsExpand ? preview(body, 300) : body)}
        </div>
        ${needsExpand ? `
            <div class="message-card__full-content">
                ${escapeHtml(body)}
            </div>
            <div class="message-card__expand-indicator" data-message-action="toggle-expand">
                <span>展开完整内容</span>
            </div>
        ` : ''}
    </article>
`;
```

### 4.3 `app.css` — 三行规则

```css
.message-card {
    contain: layout style;
}

.message-card__collapsed-body {
    overflow: hidden;
    /* max-height 由 JS 注入的 --card-preview-height 控制 */
}

.message-card--expanded .message-card__collapsed-body {
    max-height: none !important;
    overflow: visible;
}

.message-card--expanded .message-card__full-content {
    display: block;
}

.message-card__full-content {
    display: none;
}

/* 大文本分块：每 500 行一个 section */
.message-card__content-chunk {
    /* 保持自然流式 */
}
```

## 5. 与虚拟滚动的关系

虚拟滚动方案（`virtual-scroll.js`）在这个场景下是**过度设计**：

- 对话页面最多几十到几百条消息，全量渲染的 DOM 节点数在可接受范围
- 用户需要的是*看到完整内容*，而不是*隐藏大部分内容只渲染可见窗口*
- `contain: layout style` + Pretext 高度预测已经解决了 reflow 问题
- 如果未来消息量达到 1000+ 条，再引入虚拟滚动作为优化层

**virtual-scroll.js 保留但不接入**，等需要时再用。

## 6. 实施

Trae 执行以下改动：

| 文件 | 操作 | 说明 |
|------|------|------|
| `message-measure.js` | 新增 `predictCollapsedHeight()` | 计算折叠态 3 行精确高度 |
| `app.js` — `renderMessageCard()` | 用 Pretext 计算折叠/完整高度，注入 CSS 变量 | 替换现有的 `buildMessageExpansionPlan` 中的 maxCollapsedLength 逻辑 |
| `app.css` | 加 3 条规则 | `contain: layout style` + CSS 变量驱动 max-height |
| `app.js` — state/import | 去掉 `virtualScroll` 字段和 `createVirtualScroll` import | 清理虚拟滚动残留 |
| `virtual-scroll.js` | 保留不删 | 将来可能用 |

验收：
- 折叠态卡片高度 = 精确 3 行（无截断溢出）
- 点击展开 → 卡片高度平滑过渡到全文高度
- 展开一张卡片 → 其他卡片位置不变（无 reflow）
- 4.8 MB artifact 展开时不卡顿（分块渲染）
