/**
 * virtual-scroll.js — 轻量虚拟滚动容器
 *
 * 基于预计算的卡片高度（来自 message-measure.js），只渲染可见窗口内的消息。
 *
 * 用法:
 *   import { createVirtualScroll } from './virtual-scroll.js';
 *   const vs = createVirtualScroll(container, {
 *     getItemHeight(idx) { return heightMap.get(messages[idx].id); },
 *     itemCount: messages.length,
 *     overscan: 5,
 *     anchorBottom: true
 *   });
 *   vs.onRender((start, end) => renderCards(messages.slice(start, end)));
 */

export function createVirtualScroll(container, opts = {}) {
    const getItemHeight = opts.getItemHeight || (() => 80);
    const overscan = opts.overscan ?? 3;

    let itemCount = 0, totalHeight = 0, offsets = [];

    function rebuild() {
        offsets = []; let o = 0;
        for (let i = 0; i < itemCount; i++) { offsets[i] = o; o += getItemHeight(i); }
        totalHeight = o;
    }

    function findIdx(y) {
        let lo = 0, hi = itemCount;
        while (lo < hi) { const m = (lo + hi) >>> 1; offsets[m] <= y ? lo = m + 1 : hi = m; }
        return Math.max(0, lo - 1);
    }

    function range() {
        const st = container.scrollTop, vh = container.clientHeight;
        return {
            start: Math.max(0, findIdx(st) - overscan),
            end: Math.min(itemCount, findIdx(st + vh) + overscan + 1)
        };
    }

    const spacerTop = document.createElement('div');
    const content = document.createElement('div');
    const spacerBot = document.createElement('div');
    container.style.position = 'relative';
    container.style.overflowY = 'auto';
    spacerTop.style.cssText = 'width:1px;height:0';
    content.style.cssText = 'position:relative';
    spacerBot.style.cssText = 'width:1px;height:0';
    container.append(spacerTop, content, spacerBot);

    let _onRender = null;
    container.addEventListener('scroll', () => {
        if (!_onRender) return;
        requestAnimationFrame(() => _onRender(range().start, range().end));
    }, { passive: true });

    return {
        setItemCount(n) { itemCount = n; rebuild(); spacerBot.style.height = totalHeight + 'px'; },
        getTotalHeight() { return totalHeight; },
        onRender(fn) { _onRender = fn; },
        refresh() { rebuild(); spacerBot.style.height = totalHeight + 'px'; if (_onRender) _onRender(range().start, range().end); },
        scrollToBottom() { container.scrollTop = totalHeight; },
        setTopOffset(px) { spacerTop.style.height = px + 'px'; }
    };
}
