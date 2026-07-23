# Dialogue 动态渲染方案 v2 — content-visibility + Pretext

> **目标**: 长会话流畅、大内容不卡、弹性展开收起、利用浏览器原生能力最小化 JS。
> **参考**: Codex 的终端流式滚动体验 + Chrome 原生 `content-visibility: auto`。

---

## 1. 核心洞察

当前页面问题本质是：**浏览器必须渲染 38 张卡片才能算出布局**。即使 CSS 修了 `overflow: hidden`，浏览器仍要对所有 DOM 节点执行 layout → paint。

`content-visibility: auto`（Chromium 85+，2020 年就稳定了）是浏览器原生的虚拟滚动——不在可视区的元素**自动跳过渲染**。配合 `contain-intrinsic-size` 提供预估高度，浏览器完全跳过不可见卡片的 layout/paint。

```
传统方案                       content-visibility 方案
38 张卡片全部 layout+paint     视口内 ~5 张卡片渲染
每次展开触发全局 reflow        单卡 contain 隔离，只重排自己
JS 虚拟滚动管理 DOM             浏览器原生管理，0 行 JS
```

## 2. 三新技术组合

| 技术 | 作用 | 浏览器支持 |
|------|------|:---:|
| `content-visibility: auto` | 浏览器跳过不可见卡片的渲染 | Chrome 85+, Edge 85+ |
| `contain-intrinsic-size` | 给未渲染卡片一个预估高度（Pretext 计算） | Chrome 95+ |
| `contain: layout style paint` | 卡片内重排不溢出影响兄弟卡片 | 全系现代浏览器 |

## 3. 架构

### 3.1 卡片 DOM 结构

```html
<article class="message-card"
         style="content-visibility: auto;
                contain-intrinsic-size: auto var(--predicted-h, 80px);
                contain: layout style paint;"
         data-message-id="msg_xxx">

  <!-- 折叠态（可视区内的卡片才渲染这部分） -->
  <div class="message-card__collapsed-body" style="max-height: var(--preview-h, 63px)">
    预览内容 3 行...
  </div>

  <!-- 展开态（点击后渲染） -->
  <div class="message-card__full-content" hidden style="content-visibility: hidden">
    完整内容...
  </div>
</article>
```

### 3.2 工作流程

```
session 加载
  ↓
renderMessages()
  ├── Pretext prepare() 每条消息正文 → handle
  ├── Pretext layout(handle, cardWidth, 3*lineHeight) → collapsedHeight
  ├── Pretext layout(handle, cardWidth, full) → fullHeight
  ↓
renderMessageCard()
  ├── 注入 --predicted-h: collapsedHeight (CSS 变量)
  ├── 注入 --preview-h: collapsedHeight - padding
  ├── 注入 content-visibility: auto
  ├── 注入 contain-intrinsic-size: auto collapsedHeight
  └── 返回 HTML
  ↓
浏览器渲染
  ├── 只渲染视口内 + 上下 1 屏的卡片
  ├── 视口外卡片: 跳过 layout+paint，用 contain-intrinsic-size 占位
  └── 用户滚动 → 新卡片进入视口 → 自动渲染（0 JS）
  ↓
用户点击展开
  ├── 移除 content-visibility: auto（临时）
  ├── 展开 .message-card__full-content
  ├── 浏览器计算实际高度
  ├── 更新 contain-intrinsic-size = 实际高度
  └── 恢复 content-visibility: auto
```

### 3.3 大 artifact 分块

```
artifact 4.8 MB
  ↓ Pretext layoutWithLines() → 4800 行
  ↓
  ├── Chunk 1: 前 500 行 → <div data-chunk="1"> 立即渲染
  ├── Chunk 2: 501-1000 行 → <div data-chunk="2" style="content-visibility:auto">
  ├── Chunk 3: 1001-1500 行 → <div data-chunk="3" style="content-visibility:auto">
  └── ... (9 个 chunk)
```

每个 chunk 自带 `content-visibility: auto`，用户滚动时才渲染。不滚动 = 不渲染。

---

## 4. 代码改动

### 4.1 `message-measure.js` — 保持现有

`predictCardHeight()` 和 `buildHeightMap()` 不变。新增一个便捷函数用于 CSS 变量注入：

```javascript
/**
 * 返回用于 content-visibility 的 CSS 变量值
 * @returns {{ collapsedH, fullH, needsExpand }}
 */
export function cardHeightVars(message, opts = {}) {
    const cw = (opts.cardWidth || 780) - 24;
    const fb = opts.fontBody || '14px Inter, system-ui, sans-serif';
    const lh = opts.lineHeight || 21;
    const body = message?.content || '';
    const h = prepare(body, fb);
    const full = layout(h, cw, lh);
    const previewLines = Math.min(3, full.lineCount);
    const collapsedH = previewLines * lh;
    return {
        collapsedH,
        fullH: full.height,
        fullLines: full.lineCount,
        needsExpand: full.lineCount > 3
    };
}
```

### 4.2 `app.js` — `renderMessageCard()` 改动

在生成 `.message-card` 的 `<article>` 标签时，注入 CSS 变量：

```javascript
function renderMessageCard(message, options = {}) {
    // ... existing type/role/id extraction ...

    // Pretext height prediction
    const cv = cardHeightVars(message, { cardWidth: dom.messageList.clientWidth || 780 });
    const hasLongContent = cv.needsExpand && cv.fullLines > 10;

    // CSS variables for content-visibility
    const cardStyle = [
        'content-visibility:auto',
        'contain-intrinsic-size:auto ' + Math.ceil(cv.collapsedH + 40) + 'px',
        'contain:layout style paint'
    ].join(';');

    // collapsed body max-height (3 lines)
    const previewStyle = 'max-height:' + Math.ceil(cv.collapsedH + 4) + 'px;overflow:hidden';

    return `
        <article class="message-card ${cv.needsExpand ? 'message-card--expandable' : ''}"
                 style="${cardStyle}"
                 data-message-id="${escapeHtml(message.id)}"
                 data-card-full-lines="${cv.fullLines}">

            <!-- meta, execution strip, outcome strip (unchanged) -->

            <div class="message-card__collapsed-body" style="${previewStyle}">
                ${escapeHtml(body)}
            </div>

            ${cv.needsExpand ? `
                <div class="message-card__full-content" hidden
                     style="content-visibility:hidden">
                    ${hasLongContent
                        ? renderChunkedContent(body, cv)
                        : escapeHtml(body)}
                </div>
                <div class="message-card__expand-indicator">
                    <span>展开完整内容${hasLongContent ? ` · ${cv.fullLines} 行` : ''}</span>
                </div>
            ` : ''}
        </article>
    `;
}
```

### 4.3 `app.js` — 大文本分块渲染

```javascript
const CHUNK_LINES = 500;

function renderChunkedContent(text, cv) {
    const totalChunks = Math.ceil(cv.fullLines / CHUNK_LINES);
    const lines = text.split('\n');
    let html = '';
    for (let i = 0; i < totalChunks; i++) {
        const start = i * CHUNK_LINES;
        const end = Math.min(start + CHUNK_LINES, lines.length);
        const chunkText = lines.slice(start, end).join('\n');
        // First chunk always renders; rest use content-visibility
        const lazy = i > 0 ? ' style="content-visibility:auto;contain-intrinsic-size:auto 300px"' : '';
        html += `<div class="msg-chunk" data-chunk="${i}"${lazy}>${escapeHtml(chunkText)}</div>`;
    }
    return html;
}
```

### 4.4 `app.js` — 展开/收起事件处理

现有的 `message-card__expand-indicator` 点击处理已经存在（通过 `data-message-action="toggle-expand"`），只需增强：

```javascript
// 在现有的 expand toggle 处理中增加
function toggleMessageExpand(messageId) {
    const card = document.querySelector(`[data-message-id="${messageId}"]`);
    if (!card) return;
    const isExpanded = card.classList.contains('message-card--expanded');

    if (!isExpanded) {
        // 展开：临时移除 content-visibility 让浏览器计算真实高度
        card.style.contentVisibility = 'visible';
        card.querySelector('.message-card__full-content').hidden = false;
        card.querySelector('.message-card__full-content').style.contentVisibility = 'visible';
        card.classList.add('message-card--expanded');

        // 渲染完成后更新 contain-intrinsic-size
        requestAnimationFrame(() => {
            const actualH = card.offsetHeight;
            card.style.containIntrinsicSize = 'auto ' + actualH + 'px';
            card.style.contentVisibility = 'auto';
        });
    } else {
        // 收起
        card.classList.remove('message-card--expanded');
        card.querySelector('.message-card__full-content').hidden = true;
        card.querySelector('.message-card__full-content').style.contentVisibility = 'hidden';
        // 恢复折叠态预估高度
        const previewH = parseInt(card.style.getPropertyValue('--preview-h')) + 40 || 80;
        card.style.containIntrinsicSize = 'auto ' + previewH + 'px';
    }
}
```

### 4.5 `app.css` — 极简 CSS

```css
/* 卡片性能隔离 */
.message-card {
    contain: layout style paint;
}

/* 分块（visible chunk 正常流式，lazy chunk 由 content-visibility 控制） */
.msg-chunk {
    white-space: pre-wrap;
    word-break: break-word;
}

/* 展开指示器 */
.message-card__expand-indicator {
    cursor: pointer;
    padding: 8px 0;
    color: var(--accent);
    font-size: 13px;
}
.message-card__expand-indicator:hover {
    text-decoration: underline;
}
```

---

## 5. 与 Pretext / Html-in-Canvas 的对齐

| 概念 | 落地方式 |
|------|---------|
| **Pretext 文本测量** | `message-measure.js` 的 `prepare()` + `layout()` — 精确预知每张卡片的高度 |
| **Pretext 分块布局** | `layoutWithLines()` 概念 → 按 500 行分 chunk，每 chunk 独立 `content-visibility` |
| **Html-in-Canvas 的"按需渲染"** | `content-visibility: auto` — 浏览器原生的可见性裁剪，效果等价于 Canvas 内的视锥剔除 |
| **Html-in-Canvas 的"隔离重绘"** | `contain: layout style paint` — 单卡更新不触发全局 reflow，等价于 Canvas 内独立 draw call |
| **Html-in-Canvas 的"硬件加速"** | `content-visibility: auto` 跳过的元素连 paint 都不做，比硬件加速更彻底 |

---

## 6. 改动清单（Trae 执行）

| # | 文件 | 操作 | 预计 |
|:--|------|------|:--:|
| 1 | `message-measure.js` | 新增 `cardHeightVars()` 函数 | 5m |
| 2 | `app.js` | `renderMessageCard()` 注入 `content-visibility` + CSS 变量 | 20m |
| 3 | `app.js` | 新增 `renderChunkedContent()` | 10m |
| 4 | `app.js` | 增强 `toggleMessageExpand()` — 展开/收起时更新 `contain-intrinsic-size` | 10m |
| 5 | `app.css` | 加 4 条规则（见 4.5） | 5m |
| 6 | `app.js` | state/import 清理：去掉 `virtualScroll` 残留 | 5m |

**总预计**: 55m

## 7. 验收

- [ ] 38 条消息加载完成，DevTools Performance 面板中 **Layout 耗时 < 50ms**（vs 当前 ~300ms）
- [ ] 滚动时帧率 60fps，无卡顿
- [ ] 点击展开 artifact → 卡片平滑扩大 → 其他卡片位置不跳动
- [ ] 展开 4.8 MB artifact 时浏览器不卡死（分块渲染）
- [ ] 收起卡片 → 恢复折叠态高度 → `contain-intrinsic-size` 正确更新
