const STATUS_ORDER = ["needs_approval", "rejected", "accepted"];

export function buildAgentActionPlan(actions = []) {
    const normalized = Array.isArray(actions)
        ? actions.filter(Boolean).map(normalizeAction)
        : [];
    const groups = {};
    STATUS_ORDER.forEach((status) => {
        groups[status] = normalized.filter((action) => action.status === status);
    });
    const visible = [
        ...groups.needs_approval,
        ...groups.rejected,
        ...groups.accepted,
        ...normalized.filter((action) => !STATUS_ORDER.includes(action.status))
    ].slice(0, 8);

    return {
        actions: normalized,
        visible,
        counts: {
            total: normalized.length,
            accepted: groups.accepted.length,
            rejected: groups.rejected.length,
            needsApproval: groups.needs_approval.length
        },
        hasActions: normalized.length > 0,
        summary: buildSummary(normalized, groups)
    };
}

function normalizeAction(action) {
    const payload = action.payload || {};
    return {
        id: action.id || "",
        actionType: action.action_type || action.actionType || "ACTION",
        status: String(action.status || "unknown").toLowerCase(),
        summary: action.summary || "",
        riskLevel: action.risk_level || action.riskLevel || "low",
        requiresApproval: Boolean(action.requires_approval ?? action.requiresApproval),
        rejectionReason: action.rejection_reason || action.rejectionReason || "",
        createdAt: action.created_at || action.createdAt || "",
        payload
    };
}

function buildSummary(actions, groups) {
    if (actions.length === 0) {
        return "当前任务还没有 reconciled action。";
    }
    const parts = [];
    if (groups.needs_approval.length > 0) {
        parts.push(`${groups.needs_approval.length} 个待审批`);
    }
    if (groups.rejected.length > 0) {
        parts.push(`${groups.rejected.length} 个已拒绝`);
    }
    if (groups.accepted.length > 0) {
        parts.push(`${groups.accepted.length} 个已接受`);
    }
    return parts.length > 0 ? parts.join(" / ") : `${actions.length} 个 action`;
}
