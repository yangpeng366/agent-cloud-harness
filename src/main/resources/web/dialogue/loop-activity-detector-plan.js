/**
 * Loop activity detector: 基于 last_loop_tick 与当前时间的差值判断 loop 活跃度。
 *
 * 活跃度判断规则：
 * - 差值 <= activeThresholdMs -> "active"（loop 正在活跃运转）
 * - 差值 <= stallThresholdMs -> "stall"（loop 可能卡住但还没确认）
 * - 差值 > stallThresholdMs -> "stale"（loop 已明显停止运转）
 * - 无 last_loop_tick -> "unknown"
 *
 * @param {string|null} lastLoopTick - ISO 8601 timestamp of last loop tick
 * @param {number} nowMs - current time in milliseconds
 * @param {number} [activeThresholdMs=10000] - threshold for "active" (default 10s)
 * @param {number} [stallThresholdMs=30000] - threshold for "stall" (default 30s)
 * @returns {{ activity: string, ageMs: number|null }}
 */
export function detectLoopActivity(lastLoopTick, nowMs, activeThresholdMs = 10000, stallThresholdMs = 30000) {
    if (!lastLoopTick) {
        return { activity: "unknown", ageMs: null };
    }
    const tickMs = new Date(lastLoopTick).getTime();
    if (isNaN(tickMs)) {
        return { activity: "unknown", ageMs: null };
    }
    const ageMs = nowMs - tickMs;
    if (ageMs <= activeThresholdMs) {
        return { activity: "active", ageMs };
    }
    if (ageMs <= stallThresholdMs) {
        return { activity: "stall", ageMs };
    }
    return { activity: "stale", ageMs };
}

/**
 * 根据 loop activity 返回 UI 展示建议。
 *
 * @param {{ activity: string, ageMs: number|null }} loopActivity
 * @param {string} taskStatus - task 的当前状态
 * @returns {{ displayStatus: string, hint: string }}
 */
export function loopActivityDisplayHint(loopActivity, taskStatus) {
    if (taskStatus === "done" || taskStatus === "failed") {
        return { displayStatus: taskStatus, hint: "" };
    }
    if (taskStatus === "waiting_human" || taskStatus === "human_gate") {
        return { displayStatus: "paused", hint: "等待人工介入" };
    }
    switch (loopActivity.activity) {
        case "active":
            return { displayStatus: "running", hint: "loop 正在执行" };
        case "stall":
            return { displayStatus: "stall", hint: "loop 可能卡住，请检查" };
        case "stale":
            return { displayStatus: "stale", hint: "loop 已停止运转" };
        default:
            return { displayStatus: "unknown", hint: "" };
    }
}
