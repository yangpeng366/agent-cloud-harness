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
        summary: `${status} / ${action}`,
        cards: [
            { label: "Recovery Job", value: status },
            { label: "Request", value: requestId },
            { label: "Action", value: action },
            { label: "Mode", value: executionMode || "auto" },
            failureEvidence ? { label: "Failure Evidence", value: failureEvidence } : null
        ].filter(Boolean),
        chips: [
            targetWorker ? `worker ${targetWorker}` : "",
            failureClass ? `failure ${failureClass}` : "",
            failureEvidence ? `evidence ${preview(failureEvidence, 48)}` : "",
            acceptedAt ? `accepted ${formatTime(acceptedAt)}` : "",
            startedAt ? `started ${formatTime(startedAt)}` : "",
            completedAt ? `completed ${formatTime(completedAt)}` : ""
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
