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
        ["错误", firstNonBlank(metadata.provider_error, metadata.providerError)],
        ["回合状态", firstNonBlank(metadata.provider_turn_status, metadata.providerTurnStatus)],
        ["失败类型", firstNonBlank(metadata.provider_failure_class, metadata.providerFailureClass)],
        ["失败原因", firstNonBlank(metadata.provider_failure_reason, metadata.providerFailureReason)],
        ["可重试", firstNonBlank(metadata.provider_retryable, metadata.providerRetryable)]
    ]
        .filter(([, value]) => value !== null && value !== undefined && String(value).trim())
        .map(([label, value]) => `${label}: ${String(value).trim()}`);
    return lines.length > 0 ? `Provider 诊断\n${lines.join("\n")}` : "";
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
        "task_result",
        "worker_round"
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
        uniquePush(parts, `失败摘要\n${failureSummary}`);
    }
    if (providerDiagnostics) {
        uniquePush(parts, providerDiagnostics);
    }
    if (outputText) {
        uniquePush(parts, `worker 输出\n${outputText}`);
    }
    if (artifactContent) {
        uniquePush(parts, `产物内容\n${artifactContent}`);
    }
    if (nextStep) {
        uniquePush(parts, `下一步\n${nextStep}`);
    }
    return joinExpandedSections(parts);
}

function buildWorkerRoundFullContent(message, metadata, content) {
    const explicitFullContent = firstNonBlank(metadata.full_content, metadata.fullContent);
    if (explicitFullContent) {
        return explicitFullContent;
    }
    const providerDiagnostics = buildProviderDiagnosticsSection(metadata);
    const outputPreview = firstNonBlank(
        metadata.output_preview,
        metadata.outputPreview,
        metadata.summary_preview,
        metadata.summaryPreview,
        content
    );
    const outputChars = firstNonBlank(metadata.output_chars, metadata.outputChars);
    const partialChars = firstNonBlank(metadata.partial_output_chars, metadata.partialOutputChars);
    const threshold = firstNonBlank(
        metadata.partial_timeout_min_output_chars,
        metadata.partialTimeoutMinOutputChars
    );
    const runFiles = [
        ["提示词", firstNonBlank(metadata.provider_prompt_path, metadata.providerPromptPath)],
        ["事件日志", firstNonBlank(metadata.provider_event_log_path, metadata.providerEventLogPath)],
        ["最后输出", firstNonBlank(metadata.provider_last_message_path, metadata.providerLastMessagePath)],
        ["标准输出", firstNonBlank(metadata.provider_stdout_path, metadata.providerStdoutPath)],
        ["运行元数据", firstNonBlank(metadata.provider_run_metadata_path, metadata.providerRunMetadataPath)]
    ]
        .filter(([, value]) => value)
        .map(([label, value]) => `${label}: ${value}`);
    const parts = [];
    uniquePush(parts, outputPreview);
    if (providerDiagnostics) {
        uniquePush(parts, providerDiagnostics);
    }
    if (outputChars || partialChars || threshold) {
        const metrics = [
            outputChars ? `输出字符数: ${outputChars}` : null,
            partialChars ? `部分输出字符数: ${partialChars}` : null,
            threshold ? `部分超时阈值: ${threshold}` : null
        ].filter(Boolean);
        uniquePush(parts, `输出指标\n${metrics.join("\n")}`);
    }
    if (runFiles.length > 0) {
        uniquePush(parts, `Provider 运行文件\n${runFiles.join("\n")}`);
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
    } else if (type === "worker_round") {
        fullContent = buildWorkerRoundFullContent(message, metadata, content);
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

export function hasExpandedWorkerRoundContent(message) {
    const metadata = message?.metadata || {};
    const type = normalizeMessageType(message?.message_type || message?.messageType);
    if (type !== "worker_round") {
        return false;
    }
    return Boolean(firstNonBlank(
        metadata.full_content,
        metadata.fullContent,
        metadata.output_preview,
        metadata.outputPreview,
        metadata.summary_preview,
        metadata.summaryPreview,
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
        metadata.output_chars,
        metadata.outputChars,
        metadata.partial_output_chars,
        metadata.partialOutputChars,
        metadata.partial_timeout_min_output_chars,
        metadata.partialTimeoutMinOutputChars,
        metadata.provider_prompt_path,
        metadata.providerPromptPath,
        metadata.provider_event_log_path,
        metadata.providerEventLogPath,
        metadata.provider_last_message_path,
        metadata.providerLastMessagePath,
        metadata.provider_stdout_path,
        metadata.providerStdoutPath,
        metadata.provider_run_metadata_path,
        metadata.providerRunMetadataPath
    ));
}
