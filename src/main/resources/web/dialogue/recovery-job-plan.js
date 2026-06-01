export function buildRecoveryJobPlan(jobs, options = {}) {
    const items = Array.isArray(jobs) ? jobs.filter(Boolean) : [];
    const latest = items[0] || null;
    if (!latest) {
        return {
            visible: false,
            latest: null,
            cards: [],
            chips: [],
            error: ""
        };
    }

    const status = field(latest, "status") || "accepted";
    const requestId = field(latest, "id") || field(latest, "request_id") || field(latest, "requestId") || "unknown";
    const action = field(latest, "recommended_action") || field(latest, "recommendedAction") || field(latest, "metadata.recovery_action") || "recover";
    const executionMode = field(latest, "recovery_execution_mode") || field(latest, "recoveryExecutionMode") || field(latest, "metadata.recovery_execution_mode");
    const targetWorker = field(latest, "target_worker") || field(latest, "targetWorker") || field(latest, "metadata.target_worker");
    const failureClass = field(latest, "provider_failure_class") || field(latest, "providerFailureClass") || field(latest, "failure_class") || field(latest, "failureClass");
    const failureEvidence = firstNonBlank(
        field(latest, "provider_error"),
        field(latest, "providerError"),
        field(latest, "metadata.provider_error"),
        field(latest, "metadata.providerError"),
        field(latest, "provider_failure_reason"),
        field(latest, "providerFailureReason"),
        field(latest, "metadata.provider_failure_reason"),
        field(latest, "metadata.providerFailureReason"),
        field(latest, "failure_evidence"),
        field(latest, "failureEvidence"),
        field(latest, "metadata.failure_evidence"),
        field(latest, "metadata.failureEvidence")
    );
    const failureEvidenceSource = firstNonBlank(
        field(latest, "failure_evidence_source"),
        field(latest, "failureEvidenceSource"),
        field(latest, "metadata.failure_evidence_source"),
        field(latest, "metadata.failureEvidenceSource")
    );
    const acceptedAt = field(latest, "accepted_at") || field(latest, "acceptedAt");
    const startedAt = field(latest, "started_at") || field(latest, "startedAt");
    const completedAt = field(latest, "completed_at") || field(latest, "completedAt");
    const error = field(latest, "error_message") || field(latest, "errorMessage") || "";
    const formatTime = typeof options.formatTime === "function" ? options.formatTime : (value) => String(value || "");

    return {
        visible: true,
        latest,
        status,
        tone: toneForJobStatus(status),
        requestId,
        summary: `${humanizeJobStatus(status)} / ${humanizeRecoveryAction(action)}`,
        cards: [
            { label: "恢复任务", value: humanizeJobStatus(status) },
            { label: "请求", value: requestId },
            { label: "动作", value: humanizeRecoveryAction(action) },
            { label: "模式", value: humanizeExecutionMode(executionMode || "auto") },
            failureEvidence ? { label: "失败证据", value: failureEvidence } : null
        ].filter(Boolean),
        chips: [
            targetWorker ? `执行方 ${targetWorker}` : "",
            failureClass ? `失败 ${humanizeFailureClass(failureClass)}` : "",
            failureEvidence ? `证据 ${preview(failureEvidence, 48)}` : "",
            acceptedAt ? `受理 ${formatTime(acceptedAt)}` : "",
            startedAt ? `开始 ${formatTime(startedAt)}` : "",
            completedAt ? `完成 ${formatTime(completedAt)}` : ""
        ].filter(Boolean),
        failureEvidence,
        failureEvidenceSource,
        error
    };
}

function field(source, path) {
    return path.split(".").reduce((current, key) => {
        if (current == null) {
            return "";
        }
        return current[key];
    }, source) || "";
}

function firstNonBlank(...values) {
    return values
        .map((value) => String(value || "").trim())
        .find(Boolean) || "";
}

function preview(value, limit) {
    const text = String(value || "").replace(/\s+/g, " ").trim();
    if (!text || text.length <= limit) {
        return text;
    }
    return `${text.slice(0, Math.max(0, limit - 1)).trimEnd()}…`;
}

function toneForJobStatus(status) {
    switch (String(status || "").toLowerCase()) {
        case "running":
            return "active";
        case "accepted":
            return "paused";
        case "succeeded":
        case "success":
        case "completed":
            return "done";
        case "failed":
        case "error":
            return "failed";
        case "interrupted":
            return "manual";
        default:
            return "default";
    }
}

function humanizeJobStatus(status) {
    switch (String(status || "").toLowerCase()) {
        case "running":
            return "运行中";
        case "accepted":
            return "已受理";
        case "succeeded":
        case "success":
        case "completed":
            return "已完成";
        case "failed":
        case "error":
            return "失败";
        case "interrupted":
            return "需人工确认";
        default:
            return humanizeToken(status);
    }
}

function humanizeRecoveryAction(action) {
    switch (String(action || "").toLowerCase()) {
        case "fresh_session_resume":
            return "新会话恢复";
        case "handoff":
            return "移交";
        case "auto_handoff":
            return "自动移交";
        case "provider_thread_resume":
            return "继续 provider thread";
        case "resume":
            return "继续执行";
        case "recover":
            return "自动恢复";
        default:
            return humanizeToken(action);
    }
}

function humanizeExecutionMode(mode) {
    switch (String(mode || "").toLowerCase()) {
        case "fresh_session":
            return "新会话";
        case "provider_thread":
            return "原 provider thread";
        case "auto":
            return "自动";
        default:
            return humanizeToken(mode);
    }
}

function humanizeFailureClass(failureClass) {
    switch (String(failureClass || "").toLowerCase()) {
        case "provider_runtime_transient":
        case "worker_runtime_transient":
            return "临时运行失败";
        case "task_environment_blocked":
            return "环境阻塞";
        case "worker_backend_deterministic":
            return "能力不匹配";
        case "partial_result_or_quality_risk":
            return "部分结果待确认";
        default:
            return humanizeToken(failureClass);
    }
}

function humanizeToken(value) {
    return String(value || "")
        .replace(/[_-]+/g, " ")
        .trim() || "未知";
}
