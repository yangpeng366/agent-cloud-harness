# Dialogue 动态自适应布局 — Trae 实施方案

> **已生成**: `message-measure.js`、`virtual-scroll.js` 两个新模块（Canvas 2D 文本测量 + 虚拟滚动），位于 `src/main/resources/web/dialogue/`。
> **需 Trae 完成**: 将两个新模块接入 `app.js` + `app.css`：4 个位置的具体改动如下。

---

## 需改动的文件

### 1. `app.js` — 位置 A：顶部加 import

**位置**：文件顶部导入区域（约 line 1-50），在现有 import 语句后面加两行：

```javascript
import { buildHeightMap } from "./message-measure.js";
import { createVirtualScroll } from "./virtual-scroll.js";
```

### 2. `app.js` — 位置 B：state 加字段

**位置**：`const state = { ... }` 对象末尾（约 line 55），加两个字段：

```javascript
const state = {
    // ... 现有所有字段保持不变
    messageHeightMap: new Map(),
    virtualScroll: null,
};
```

### 3. `app.js` — 位置 C：修改 `renderMessages()`

**位置**：`renderMessages()` 函数体内（约 line 980-1015），`dom.messageList.innerHTML = ...` 这一行附近。

把当前的渲染逻辑（直接 `innerHTML` 拼接所有消息卡片）替换为：

```javascript
function renderMessages() {
    // ... existing filter/summary logic (keep all of it) ...

    // --- NEW: build height map ---
    state.messageHeightMap = buildHeightMap(filteredMessages, {
        cardWidth: (dom.messageList.clientWidth || 780),
        fontBody: "14px Inter, system-ui, sans-serif",
        lineHeight: 21
    });

    // --- NEW: virtual scroll ---
    if (!state.virtualScroll) {
        state.virtualScroll = createVirtualScroll(dom.messageList, {
            getItemHeight(idx) {
                const msg = filteredMessages[idx];
                return state.messageHeightMap.get(msg?.id) || 80;
            },
            itemCount: filteredMessages.length,
            overscan: 5
        });
        state.virtualScroll.onRender((start, end) => {
            const html = filteredMessages.slice(start, end)
                .map(m => renderMessageCard(m))
                .join("");
            dom.messageList.querySelector('.vs-content').innerHTML = html;
        });
    }

    state.virtualScroll.setItemCount(filteredMessages.length);
    state.virtualScroll.refresh();
}
```

**关键注意**：
- 保留现有的筛选逻辑（`messageFilterRole`、`messageFilterScope`、`emptyState`）
- 保留 `dom.messageSummary` 的渲染逻辑
- 保留 `dom.messagePanelHint` 文本更新
- 保留 `queueWorkerRoundArtifactLoads(filteredMessages)` 调用
- 只替换 `dom.messageList.innerHTML = ...` 这一行的渲染方式

### 4. `app.css` — 位置 D：末尾加 CSS

**位置**：`app.css` 文件末尾追加：

```css
/* ===== NEW: virtual scroll & dynamic layout ===== */

/* 消息卡片：隔离 reflow */
.message-card {
    contain: layout style;
}

/* composer 紧凑模式 */
.composer-panel {
    min-height: 52px;
    max-height: fit-content;
    transition: max-height 0.25s ease;
}
.composer-panel--expanded {
    max-height: 320px;
}

/* thread-drawer 动态高度 */
.thread-drawer[open] {
    max-height: 40vh;
    overflow-y: auto;
    border-bottom: 1px solid var(--line);
    margin-bottom: 6px;
    padding-bottom: 8px;
    transition: max-height 0.25s ease;
}

/* virtual scroll spacer */
.vs-spacer { flex-shrink: 0; }
.vs-content { position: relative; }
```

### 5. `index.html` — 无需改动（已修改）

---

## 验收

```bash
# 构建
powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -SkipTests

# 启动
.\.tmp\start-harness.cmd

# 截图验证
node task-ops.js screenshot-harness
```

完成后来检查：
1. 打开 `http://localhost:8080/dialogue/` 确认 55 sessions 正常加载
2. 打开 `#session=session_8bc98b670d1c4be2` 确认 38 条消息中只渲染 ~15 个 DOM 卡片
3. 控制台 `state.messageHeightMap.size` 应等于消息数
4. 滚动流畅无卡顿
