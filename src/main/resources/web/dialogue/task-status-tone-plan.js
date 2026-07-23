/**
 * Task status -> UI tone mapping.
 *
 * 状态语义统一口径：
 * - active / running   -> active  执行中
 * - waiting_human       -> paused  等待人工确认（不是 failed）
 * - paused / waiting    -> paused  暂停
 * - done                -> done    已完成
 * - partial             -> partial 部分达成
 * - failed              -> failed  执行失败
 * - 其他                 -> default 默认灰
 *
 * 关键约束：waiting_human / human_gate 不能被渲染成 failed；
 * active 任务在 /continue 超时后仍是 active，不是 failed。
 */
export function toneForStatus(status) {
    switch ((status || "").toLowerCase()) {
        case "active":
        case "running":
            return "active";
        case "paused":
        case "waiting":
        case "waiting_human":
            return "paused";
        case "partial":
            return "partial";
        case "done":
            return "done";
        case "failed":
            return "failed";
        default:
            return "default";
    }
}

/**
 * Pinned task outcome tone，区分“还在跑”和“已经结束”。
 * waiting_human / human_gate / paused 都收成 paused 语气，避免误读成失败。
 */
export function toneForPinnedTaskOutcome(status, controlNode) {
    const statusLower = (status || "").toLowerCase();
    const controlLower = (controlNode || "").toLowerCase();
    if (statusLower === "done") {
        return "done";
    }
    if (statusLower === "partial") {
        return "partial";
    }
    if (statusLower === "failed") {
        return "failed";
    }
    if (statusLower === "waiting_human" || controlLower === "human_gate" || statusLower === "paused") {
        return "paused";
    }
    if (["active", "running"].includes(statusLower)) {
        return "active";
    }
    return "default";
}