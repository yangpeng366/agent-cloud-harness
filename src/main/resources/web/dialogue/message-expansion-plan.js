function firstNonBlank(...values) {
    for (const value of values) {
        if (value === null || value === undefined) {
            continue;
        }
        const text = String(value);
        if (text.trim()) {
            return text;
        }
    }
    return "";
}

function normalizeMessageType(type) {
    return firstNonBlank(type, "").toLowerCase();
}

function normalizeForCompare(value) {
    return firstNonBlank(value, "").replace(/\s+/g, " ").trim();
}

function looksLikeTerseOutcomeToken(value) {
    const text = firstNonBlank(value, "");
    if (!text) {
        return false;
    }
    return /^(failed|done|ok|success|succeeded|completed?)$/i.test(text);
}

function isStaleTaskOutcomeShell(value) {
    const text = firstNonBlank(value, "");
    if (!text) {
        return false;
    }
    const workerSectionEmpty = /Worker Output\s*(Artifact Content|Failure Summary|下一步|$)/s.test(text);
    const artifactSectionEmpty = /Artifact Content\s*(Failure Summary|下一步|$)/s.test(text);
    return workerSectionEmpty && artifactSectionEmpty;
}

function joinExpandedSections(parts) {
    return parts
        .map((part) => firstNonBlank(part, ""))
        .filter(Boolean)
        .join("\n\n");
}

function buildProviderDiagnosticsSection(metadata) {
    const lines = [
        ["error", firstNonBlank(metadata.provider_error, metadata.providerError)],
        ["turn status", firstNonBlank(metadata.provider_turn_status, metadata.providerTurnStatus)],
        ["failure class", firstNonBlank(metadata.provider_failure_class, metadata.providerFailureClass)],
        ["failure reason", firstNonBlank(metadata.provider_failure_reason, metadata.providerFailureReason)],
        ["retryable", firstNonBlank(metadata.provider_retryable, metadata.providerRetryable)]
    ]
        .filter(([, value]) => value !== null && value !== undefined && String(value).trim())
        .map(([label, value]) => `${label}: ${String(value).trim()}`);
    return lines.length > 0 ? `Provider Diagnostics\n${lines.join("\n")}` : "";
}

function uniquePush(parts, candidate) {
    const normalized = normalizeForCompare(candidate);
    if (!normalized) {
        return;
    }
    if (parts.some((item) => normalizeForCompare(item) === normalized)) {
        return;
    }
    parts.push(candidate);
}

function isExpandableMessageType(type) {
    return [
        "thinking",
        "reasoning",
        "tool_call",
        "tool_result",
        "action",
        "execution",
        "system",
        "internal",
        "task_progress",
        "task_result"
    ].includes(normalizeMessageType(type));
}

function buildTaskOutcomeFullContent(message, metadata, content) {
    const explicitFullContent = firstNonBlank(metadata.full_content, metadata.fullContent);
    if (explicitFullContent) {
        const failureSummary = firstNonBlank(
            metadata.failure_summary_readable,
            metadata.failureSummaryReadable
        );
        if (!failureSummary || !isStaleTaskOutcomeShell(explicitFullContent)) {
            return explicitFullContent;
        }
    }
    const failureSummary = firstNonBlank(
        metadata.failure_summary_readable,
        metadata.failureSummaryReadable
    );
    const providerDiagnostics = buildProviderDiagnosticsSection(metadata);
    const outputText = firstNonBlank(metadata.output_text, metadata.outputText);
    const artifactContent = firstNonBlank(metadata.artifact_content, metadata.artifactContent);
    const nextStep = firstNonBlank(metadata.next_step, metadata.nextStep);
    const parts = [];
    if (!failureSummary || !looksLikeTerseOutcomeToken(content)) {
        uniquePush(parts, content);
    }
    if (failureSummary) {
        uniquePush(parts, `Failure Summary\n${failureSummary}`);
    }
    if (providerDiagnostics) {
        uniquePush(parts, providerDiagnostics);
    }
    if (outputText) {
        uniquePush(parts, `Worker Output\n${outputText}`);
    }
    if (artifactContent) {
        uniquePush(parts, `Artifact Content\n${artifactContent}`);
    }
    if (nextStep) {
        uniquePush(parts, `下一步\n${nextStep}`);
    }
    return joinExpandedSections(parts);
}

export function buildMessageExpansionPlan(message, body, options = {}) {
    const metadata = message?.metadata || {};
    const type = normalizeMessageType(message?.message_type || message?.messageType);
    const content = firstNonBlank(message?.content, "");
    const normalizedBody = firstNonBlank(body, "");
    const maxCollapsedLength = Number.isFinite(options.maxCollapsedLength) ? options.maxCollapsedLength : 300;

    let fullContent = content;
    if (type === "task_progress" || type === "task_result") {
        fullContent = buildTaskOutcomeFullContent(message, metadata, content);
    }

    if (!isExpandableMessageType(type) || !fullContent) {
        return {
            fullContent: fullContent || normalizedBody,
            needsExpand: false
        };
    }

    const normalizedFull = normalizeForCompare(fullContent);
    const normalizedCollapsed = normalizeForCompare(normalizedBody);
    const needsExpand = Boolean(normalizedFull)
        && (normalizedFull !== normalizedCollapsed || fullContent.length > maxCollapsedLength);

    return {
        fullContent,
        needsExpand
    };
}

export function hasExpandedTaskOutcomeContent(message) {
    const metadata = message?.metadata || {};
    const type = normalizeMessageType(message?.message_type || message?.messageType);
    if (type !== "task_progress" && type !== "task_result") {
        return false;
    }
    return Boolean(firstNonBlank(
        metadata.full_content,
        metadata.fullContent,
        metadata.failure_summary_readable,
        metadata.failureSummaryReadable,
        metadata.provider_error,
        metadata.providerError,
        metadata.provider_turn_status,
        metadata.providerTurnStatus,
        metadata.provider_failure_class,
        metadata.providerFailureClass,
        metadata.provider_failure_reason,
        metadata.providerFailureReason,
        metadata.provider_retryable,
        metadata.providerRetryable,
        metadata.output_text,
        metadata.outputText,
        metadata.artifact_content,
        metadata.artifactContent
    ));
}
