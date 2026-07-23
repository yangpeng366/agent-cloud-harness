/**
 * message-measure.js — Canvas 2D text measurement (Pretext concepts, vanilla impl)
 *
 * 核心能力:
 *   prepare(text, font) → handle
 *   layout(handle, maxWidth, lineHeight) → { height, lineCount, text }
 *   predictCardHeight(message, opts) → number
 *   buildHeightMap(messages, opts) → Map<id, height>
 *
 * 与 Pretext 的对齐:
 *   - 使用 Canvas 2D measureText 作为 truth source（同 Pretext）
 *   - prepare → 缓存测量结果（同 Pretext 的 cold path）
 *   - layout → 纯算术断行计算（同 Pretext 的 hot path）
 *   - font 必须与 CSS font 声明完全一致（同 Pretext 约束）
 */

const _cache = new Map();

function avgCharWidth(ctx, font) {
    ctx.font = font;
    const s = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,;:!?-';
    return ctx.measureText(s).width / s.length;
}

export function prepare(text, font) {
    const key = font + '::' + (text || '').length + '::' + (text || '').slice(0, 60);
    if (_cache.has(key)) return _cache.get(key);

    const c = document.createElement('canvas');
    const ctx = c.getContext('2d');
    ctx.font = font;
    const cw = avgCharWidth(ctx, font);

    const words = [];
    for (const w of String(text || '').split(/(\s+)/)) {
        if (!w) continue;
        const isSpace = /^\s+$/.test(w);
        words.push({ text: w, width: isSpace ? cw * w.length : ctx.measureText(w).width, isSpace });
    }

    const h = { words, charWidth: cw, font };
    _cache.set(key, h);
    return h;
}

export function layout(handle, maxWidth, lineHeight) {
    if (!handle || !handle.words || !handle.words.length) return { height: 0, lineCount: 0 };
    const { words } = handle;
    const widthLimit = Math.max(1, Number(maxWidth) || 1);
    let lines = 1, cur = 0;
    for (const w of words) {
        if (w.width > widthLimit && !w.isSpace) {
            if (cur > 0) {
                lines++;
                cur = 0;
            }
            const wrappedLines = Math.max(1, Math.ceil(w.width / widthLimit));
            lines += wrappedLines - 1;
            cur = w.width % widthLimit;
            continue;
        }
        if (cur + w.width <= widthLimit) { cur += w.width; }
        else { lines++; cur = w.isSpace ? 0 : w.width; }
    }
    return { height: lines * lineHeight, lineCount: lines };
}

export function measureHeight(text, font, maxWidth, lh) {
    if (!text) return 0;
    return layout(prepare(text, font), maxWidth, lh).height;
}

export function predictCardHeight(message, opts = {}) {
    const cw = (opts.cardWidth || 780) - 24;
    const fb = opts.fontBody || '14px Inter, system-ui, sans-serif';
    const lh = opts.lineHeight || 21;
    const meta = 28, pad = 12;
    const body = measureHeight(message?.content || '', fb, cw, lh);
    const hasStrip = !!(message?.metadata?.execution_status
        || message?.metadata?.selected_worker || message?.metadata?.worker_id);
    const strip = hasStrip ? 36 : 0;
    const hasArtifact = (message?.message_type === 'worker_round'
        || message?.messageType === 'worker_round');
    const artifact = hasArtifact ? 60 : 0;
    return Math.max(48, Math.ceil(meta + pad + body + strip + artifact));
}

export function buildHeightMap(messages, opts) {
    const m = new Map();
    for (const msg of messages) m.set(msg.id || '', predictCardHeight(msg, opts));
    return m;
}

/**
 * 返回用于 content-visibility 的 CSS 变量值
 * @returns {{ collapsedH, fullH, needsExpand }}
 */
export function cardHeightVars(message, opts = {}) {
    const cw = (opts.cardWidth || 780) - 24;
    const fb = opts.fontBody || '14px Inter, system-ui, sans-serif';
    const lh = opts.lineHeight || 21;
    const fullText = opts.text ?? message?.content ?? '';
    const previewText = opts.previewText ?? fullText;
    const full = layout(prepare(fullText, fb), cw, lh);
    const preview = layout(prepare(previewText, fb), cw, lh);
    const previewLines = Math.min(3, preview.lineCount || full.lineCount);
    const collapsedH = previewLines * lh;
    return {
        collapsedH,
        fullH: full.height,
        fullLines: full.lineCount,
        previewLines,
        needsExpand: full.lineCount > 3 || preview.lineCount > 3
    };
}
