import test from "node:test";
import assert from "node:assert/strict";

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
    }
    return "";
}

function preview(value, max = 320) {
    const text = typeof value === "string" ? value.trim() : "";
    if (!text) {
        return "";
    }
    return text.length > max ? `${text.slice(0, max - 1)}...` : text;
}

function humanizeToken(value) {
    const text = firstNonBlank(value);
    return text ? text.replace(/_/g, " ") : null;
}

function humanizeFailureClass(value) {
    switch (firstNonBlank(value)) {
        case "worker_runtime_transient":
            return "临时运行失败";
        case "task_environment_blocked":
            return "环境阻塞";
        case "worker_backend_deterministic":
            return "能力不匹配";
        case "partial_result_or_quality_risk":
            return "部分结果待确认";
        case "worker_execution_failed":
            return "执行失败";
        default:
            return humanizeToken(value);
    }
}

function humanizeRecoveryStage(value) {
    switch (firstNonBlank(value)) {
        case "same_worker_retry_scheduled":
            return "同 worker 重试";
        case "auto_handoff_scheduled":
            return "自动切换 worker";
        case "human_gate_required":
            return "等待人工确认";
        default:
            return humanizeToken(value);
    }
}

function recoveryActionHint(failureClass, recoveryStage) {
    if (firstNonBlank(recoveryStage) !== "等待人工确认") {
        return "";
    }
    switch (firstNonBlank(failureClass)) {
        case "环境阻塞":
            return "先修环境后继续";
        case "部分结果待确认":
            return "先复核已有结果";
        default:
            return "";
    }
}

function latestTaskOutcomeNarrative(task, flow, max = 320) {
    const taskId = task?.id;
    const messages = Array.isArray(flow?.related_messages) ? flow.related_messages : [];
    const matched = messages
        .filter((message) => message?.task_id === taskId && ["task_progress", "task_result"].includes((message?.message_type || "").toLowerCase()))
        .sort((left, right) => Number(left?.created_at || 0) - Number(right?.created_at || 0));
    const latestOutcome = matched.at(-1) || null;
    const metadata = latestOutcome?.metadata || {};
    const taskMetadata = flow?.task?.metadata || task?.metadata || {};
    const narrative = firstNonBlank(
        latestOutcome?.content,
        metadata.content,
        metadata.summary_text,
        metadata.summaryText,
        metadata.summary_preview,
        metadata.summaryPreview,
        failureNarrativeFallback(taskMetadata)
    );
    return narrative ? preview(narrative, max) : "";
}

function activeWorkerLabel(task, flow) {
    const flowTask = flow?.task || {};
    const taskMetadata = flowTask.metadata || task?.metadata || {};
    const routePreview = flow?.route_preview || flow?.routePreview || {};
    const providerSelection = flow?.provider_selection || flow?.providerSelection || {};
    const routeSurface = flow?.runtime_cognition_surface?.route || flow?.runtimeCognitionSurface?.route || {};
    return firstNonBlank(
        flowTask.assigned_worker,
        flowTask.assignedWorker,
        providerSelection.selected_worker_id,
        providerSelection.selectedWorkerId,
        routeSurface.selected_worker,
        routeSurface.selectedWorker,
        routePreview.selected_worker,
        routePreview.selectedWorker,
        taskMetadata.assigned_worker,
        taskMetadata.assignedWorker,
        task?.assigned_worker,
        task?.assignedWorker,
        providerSelection.selected_provider,
        providerSelection.selectedProvider
    );
}

function buildThreadExecutionStrip(task, flow, workerLabel) {
    const taskStatus = firstNonBlank(task?.status, "active");
    const controlNode = firstNonBlank(task?.control_node, task?.controlNode, "intake");
    if (!workerLabel && !taskStatus && !controlNode) {
        return null;
    }
    const statusLower = taskStatus.toLowerCase();
    const schedulerPending = statusLower === "active" && controlNode === "scheduler";
    const label = schedulerPending
        ? "待继续"
        : ["active", "running"].includes(statusLower) ? "执行中" : "最近执行";
    const title = workerLabel ? `worker ${workerLabel}` : `${taskStatus} / ${controlNode}`;
    const detail = workerLabel ? `${taskStatus} / ${controlNode}` : "";
    return title || detail
        ? { label, title, detail, summary: [title, detail].filter(Boolean).join(" · ") }
        : null;
}

function taskOutcomeNextStep(task, outcomeMetadata) {
    return firstNonBlank(
        outcomeMetadata.next_step,
        outcomeMetadata.nextStep,
        outcomeMetadata.suggested_next_step,
        outcomeMetadata.suggestedNextStep,
        task?.next_step,
        task?.nextStep
    );
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

function numericValue(...values) {
    for (const value of values) {
        const number = Number(value);
        if (Number.isFinite(number)) {
            return number;
        }
    }
    return null;
}

function messageCardRecoveryDetail(metadata, compact = false) {
    const failureClass = humanizeFailureClass(firstNonBlank(
        metadata.failure_class,
        metadata.failureClass
    ));
    const recoveryStage = humanizeRecoveryStage(firstNonBlank(
        metadata.recovery_stage,
        metadata.recoveryStage
    ));
    const handoffTarget = firstNonBlank(
        metadata.auto_handoff_target,
        metadata.autoHandoffTarget
    );
    const manualHandoffCandidate = firstNonBlank(
        metadata.manual_handoff_candidate,
        metadata.manualHandoffCandidate
    );
    const sameWorkerRetryCount = numericValue(
        metadata.auto_same_worker_retry_count,
        metadata.autoSameWorkerRetryCount
    );
    const autoHandoffCount = numericValue(
        metadata.auto_handoff_count,
        metadata.autoHandoffCount
    );
    const recoveryExecutionMode = firstNonBlank(
        metadata.recovery_execution_mode,
        metadata.recoveryExecutionMode
    );
    const recoveryParts = [];
    if (failureClass) {
        recoveryParts.push(`失败 · ${failureClass}`);
    }
    if (recoveryStage) {
        recoveryParts.push(`恢复 · ${recoveryStage}`);
    }
    const actionHint = recoveryActionHint(failureClass, recoveryStage);
    if (actionHint) {
        recoveryParts.push(`建议 · ${actionHint}`);
    }
    if (sameWorkerRetryCount && sameWorkerRetryCount > 0) {
        recoveryParts.push(`重试 ${sameWorkerRetryCount}`);
    }
    if (autoHandoffCount && autoHandoffCount > 0) {
        recoveryParts.push(
            handoffTarget
                ? `移交 ${autoHandoffCount} -> ${preview(handoffTarget, compact ? 12 : 18)}`
                : `移交 ${autoHandoffCount}`
        );
    } else if (manualHandoffCandidate) {
        recoveryParts.push(`建议移交 -> ${preview(manualHandoffCandidate, compact ? 12 : 18)}`);
    }
    if (recoveryExecutionMode === "fresh_session") {
        recoveryParts.push("恢复 · fresh session");
    }
    return recoveryParts.length > 0 ? recoveryParts.join("  ") : "";
}

function compressWhitespace(value) {
    return String(value || "").replace(/\s+/g, " ").trim();
}

function stripFailureNoise(value) {
    const text = String(value || "");
    const trimmed = text.trim();
    if (!trimmed) {
        return "";
    }
    const stopPatterns = [
        /\n---+\n/,
        /\n`{3,}/,
        /\ndocs[\\/]/i,
        /\n\.github[\\/]/i,
        /\nREADME/i,
        /\nARCHITECTURE/i,
        /\nSPEC\.md/i,
        /\n我会先把/
    ];
    let cutoff = trimmed.length;
    for (const pattern of stopPatterns) {
        const match = pattern.exec(trimmed);
        if (match && typeof match.index === "number") {
            cutoff = Math.min(cutoff, match.index);
        }
    }
    return compressWhitespace(trimmed.slice(0, cutoff));
}

function looksLikeFailureNoiseLine(line) {
    const text = String(line || "").trim();
    if (!text) {
        return true;
    }
    return /^(Worker Output|Artifact Content|Failure Summary|下一步)$/i.test(text)
        || /^\[[0-9;]*m/.test(text)
        || /^docs[\\/]/i.test(text)
        || /^\.github[\\/]/i.test(text)
        || /^(README|ARCHITECTURE|SPEC\.md)\b/i.test(text)
        || /^我会先把/.test(text)
        || /^([A-Z]:)?[\\/].+/.test(text)
        || /^(dir|total)\b/i.test(text);
}

function firstFailureLine(value) {
    const lines = String(value || "")
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter(Boolean);
    const useful = lines.find((line) => !looksLikeFailureNoiseLine(line));
    return useful || compressWhitespace(value);
}

function extractQuotedThreadId(text) {
    const match = /["'](\d{3,})["']/.exec(String(text || ""));
    return match?.[1] || "";
}

function looksLikeGarbledThreadNotFound(text) {
    const raw = String(text || "");
    const replacementCount = (raw.match(/\uFFFD/g) || []).length;
    return replacementCount >= 2 && /["']\d{3,}["']/.test(raw) && /[ûҵ]/i.test(raw);
}

function summarizeFailureText(text, workerHint = "") {
    const normalized = String(text || "");
    const compact = compressWhitespace(normalized);
    if (!compact) {
        return "";
    }
    const threadId = extractQuotedThreadId(normalized);
    const workerPrefix = firstNonBlank(workerHint) ? `worker ${workerHint} failed:` : "worker failed:";
    if (threadId && (/(thread\s+not\s+found|not\s+found)/i.test(normalized)
        || /没.*找到|未.*找到/.test(normalized)
        || looksLikeGarbledThreadNotFound(normalized))) {
        return `${workerPrefix} thread not found (${threadId})`;
    }
    if (/authentication required/i.test(normalized)) {
        return `${workerPrefix} authentication required`;
    }
    if (/connection reset/i.test(normalized)) {
        return `${workerPrefix} connection reset`;
    }
    if (/timed?\s*out|timeout/i.test(normalized)) {
        return `${workerPrefix} timeout`;
    }
    if (/failed to start/i.test(normalized)) {
        return `${workerPrefix} failed to start`;
    }
    if (/startup remote plugin sync failed/i.test(normalized)) {
        return `${workerPrefix} startup remote plugin sync failed`;
    }
    return "";
}

function sanitizeFailureSummaryForDisplay(rawValue, workerHint = "") {
    const raw = firstNonBlank(rawValue, "");
    if (!raw) {
        return "";
    }
    const firstLine = firstFailureLine(raw);
    const knownSummary = summarizeFailureText(firstLine, workerHint) || summarizeFailureText(raw, workerHint);
    const compact = knownSummary || stripFailureNoise(firstLine) || stripFailureNoise(raw);
    return preview(compact, 220);
}

function failureNarrativeFallback(metadata) {
    if (!metadata || typeof metadata !== "object") {
        return "";
    }
    const workerHint = firstNonBlank(
        metadata.worker,
        metadata.worker_id,
        metadata.workerId,
        metadata.previous_worker,
        metadata.previousWorker,
        metadata.assigned_worker,
        metadata.assignedWorker
    );
    const failureSummaryRaw = firstNonBlank(
        metadata.failure_summary_readable,
        metadata.failureSummaryReadable
    );
    const failureSummary = sanitizeFailureSummaryForDisplay(failureSummaryRaw, workerHint);
    if (!failureSummary) {
        return "";
    }
    const recoveryDetail = messageCardRecoveryDetail(metadata, true);
    return recoveryDetail
        ? `${failureSummary}\n\n恢复状态：${recoveryDetail}`
        : failureSummary;
}

function effectiveTaskOutcomeFullContent(task, flow) {
    const taskId = task?.id;
    const messages = Array.isArray(flow?.related_messages) ? flow.related_messages : [];
    const matched = messages
        .filter((message) => message?.task_id === taskId && ["task_progress", "task_result"].includes((message?.message_type || "").toLowerCase()))
        .sort((left, right) => Number(left?.created_at || 0) - Number(right?.created_at || 0));
    const latestOutcome = matched.at(-1) || null;
    const outcomeMetadata = latestOutcome?.metadata || {};
    const taskMetadata = flow?.task?.metadata || task?.metadata || {};
    const explicitFullContent = firstNonBlank(
        outcomeMetadata.full_content,
        outcomeMetadata.fullContent
    );
    const failureFallback = firstNonBlank(
        outcomeMetadata.failure_summary_readable,
        outcomeMetadata.failureSummaryReadable,
        failureNarrativeFallback(taskMetadata)
    );
    const nextStep = taskOutcomeNextStep(task, outcomeMetadata);

    if (explicitFullContent && !(failureFallback && isStaleTaskOutcomeShell(explicitFullContent))) {
        return explicitFullContent;
    }
    if (failureFallback) {
        return joinExpandedSections([
            failureFallback,
            nextStep ? `下一步\n${nextStep}` : ""
        ]);
    }
    return firstNonBlank(
        explicitFullContent,
        outcomeMetadata.output_text,
        outcomeMetadata.outputText,
        outcomeMetadata.artifact_content,
        outcomeMetadata.artifactContent,
        latestOutcome?.content
    );
}

function assistantOutputPreview(task, flow, max = 220) {
    const taskMetadata = flow?.task?.metadata || task?.metadata || {};
    const latest = firstNonBlank(
        effectiveTaskOutcomeFullContent(task, flow),
        failureNarrativeFallback(taskMetadata),
        task?.summary,
        task?.next_step,
        task?.nextStep
    );
    return latest ? preview(latest, max) : "";
}

function renderPinnedTaskOutcomeSummary(task, flow) {
    if (!task || !flow || firstNonBlank(flow?.task?.id) !== firstNonBlank(task?.id)) {
        return null;
    }
    const workerLabel = activeWorkerLabel(task, flow);
    const executionStrip = buildThreadExecutionStrip(task, flow, workerLabel);
    const outcomeStrip = buildThreadOutcomeStrip(task, flow, 260);
    const outputPreview = pinnedTaskOutcomePreview(task, flow, 240);
    if (!executionStrip && !outcomeStrip && !outputPreview) {
        return null;
    }
    const taskMetadata = flow?.task?.metadata || task?.metadata || {};
    const detail = messageCardRecoveryDetail(taskMetadata, true);
    const showBody = Boolean(outputPreview) && !outcomeStrip;
    return {
        workerLabel,
        executionStrip,
        outcomeStrip,
        outputPreview,
        detail,
        showBody
    };
}

function buildThreadOutcomeStrip(task, flow, max = 220) {
    const outputPreview = assistantOutputPreview(task, flow, max);
    const taskStatus = firstNonBlank(task?.status, "active");
    const controlNode = firstNonBlank(task?.control_node, task?.controlNode, "intake");
    const detail = [taskStatus, controlNode].filter(Boolean).join(" / ");
    if (!outputPreview && !detail) {
        return null;
    }
    return {
        label: "最近输出",
        title: outputPreview,
        detail
    };
}

function looksLikeTerseOutcomeToken(value) {
    const text = firstNonBlank(value, "");
    if (!text) {
        return false;
    }
    return /^(failed|done|ok|success|succeeded|completed?)$/i.test(text);
}

function looksLikeTerseOutcomeNarrative(value) {
    const text = firstNonBlank(value, "");
    if (!text) {
        return false;
    }
    if (looksLikeTerseOutcomeToken(text)) {
        return true;
    }
    return /进展[:：]\s*(failed|done|ok|success|succeeded|completed?)\b/i.test(text);
}

function pinnedTaskOutcomePreview(task, flow, max = 240) {
    const latestOutcome = (Array.isArray(flow?.related_messages) ? flow.related_messages : [])
        .filter((message) => message?.task_id === task?.id && ["task_progress", "task_result"].includes((message?.message_type || "").toLowerCase()))
        .sort((left, right) => Number(left?.created_at || 0) - Number(right?.created_at || 0))
        .at(-1) || null;
    const outcomeMetadata = latestOutcome?.metadata || {};
    const taskMetadata = flow?.task?.metadata || task?.metadata || {};
    const outcomeMessagePreviewRaw = latestOutcome
        ? messageCardWorkerOutcomePreview(latestOutcome, max)
        : "";
    const outcomeMessagePreview = looksLikeTerseOutcomeToken(outcomeMessagePreviewRaw)
        ? ""
        : outcomeMessagePreviewRaw;
    const directFailure = sanitizeFailureSummaryForDisplay(
        firstNonBlank(
            outcomeMetadata.failure_summary_readable,
            outcomeMetadata.failureSummaryReadable,
            taskMetadata.failure_summary_readable,
            taskMetadata.failureSummaryReadable
        ),
        firstNonBlank(
            outcomeMetadata.selected_worker,
            outcomeMetadata.selectedWorker,
            outcomeMetadata.assigned_worker,
            outcomeMetadata.assignedWorker,
            taskMetadata.previous_worker,
            taskMetadata.previousWorker,
            taskMetadata.assigned_worker,
            taskMetadata.assignedWorker,
            activeWorkerLabel(task, flow)
        )
    );
    const narrative = latestTaskOutcomeNarrative(task, flow, max);
    return preview(firstNonBlank(outcomeMessagePreview, directFailure, narrative, assistantOutputPreview(task, flow, max)), max);
}

function messageCardWorkerLabel(metadata) {
    return firstNonBlank(
        metadata.selected_worker,
        metadata.selectedWorker,
        metadata.assigned_worker,
        metadata.assignedWorker,
        metadata.previous_worker,
        metadata.previousWorker,
        metadata.worker_id,
        metadata.workerId
    );
}

function messageCardCurrentState(metadata) {
    return firstNonBlank(
        metadata.task_status && metadata.control_node
            ? `${metadata.task_status} / ${metadata.control_node}`
            : null,
        metadata.taskStatus && metadata.controlNode
            ? `${metadata.taskStatus} / ${metadata.controlNode}`
            : null,
        metadata.current_state,
        metadata.currentState,
        metadata.completion_status,
        metadata.completionStatus
    );
}

function isWorkerOutcomeMessageType(type) {
    return type === "task_progress" || type === "task_result" || type === "worker_round";
}

function messageCardWorkerOutcomePreview(message, max = 220) {
    const type = (message?.message_type || "").toLowerCase();
    if (!isWorkerOutcomeMessageType(type)) {
        return "";
    }
    const metadata = message?.metadata || {};
    const candidate = firstNonBlank(
        failureNarrativeFallback(metadata),
        metadata.output_preview,
        metadata.outputPreview,
        metadata.summary_preview,
        metadata.summaryPreview,
        message?.content
    );
    return candidate ? preview(candidate, max) : "";
}

function focusedTaskOutcomeProjection(message, flow) {
    const taskId = message?.task_id || message?.taskId;
    const flowTask = flow?.task;
    if (!taskId || !flowTask || firstNonBlank(flowTask?.id) !== taskId) {
        return null;
    }
    const type = (message?.message_type || message?.messageType || "").toLowerCase();
    if (!isWorkerOutcomeMessageType(type)) {
        return null;
    }
    return {
        preview: assistantOutputPreview(flowTask, flow, 220),
        fullContent: effectiveTaskOutcomeFullContent(flowTask, flow)
    };
}

function buildMessageDisplayViewForFocusedFlow(message, flow) {
    const projection = focusedTaskOutcomeProjection(message, flow);
    if (!projection) {
        return message;
    }
    const metadata = { ...(message?.metadata || {}) };
    if (projection.preview) {
        metadata.summary_preview = projection.preview;
        metadata.summaryPreview = projection.preview;
    }
    const currentFullContent = firstNonBlank(metadata.full_content, metadata.fullContent);
    if (projection.fullContent && (!currentFullContent || isStaleTaskOutcomeShell(currentFullContent))) {
        metadata.full_content = projection.fullContent;
        metadata.fullContent = projection.fullContent;
    }
    return {
        ...message,
        metadata
    };
}

function messageCardOutcomeStrip(message, compact = false) {
    const type = (message?.message_type || "").toLowerCase();
    if (!isWorkerOutcomeMessageType(type)) {
        return null;
    }
    const metadata = message?.metadata || {};
    const stateLine = messageCardCurrentState(metadata);
    const previewText = messageCardWorkerOutcomePreview(message, compact ? 120 : 180);
    const label = type === "worker_round" ? "执行回合" : "最近输出";
    return previewText || stateLine
        ? {
            label,
            title: previewText,
            detail: stateLine
        }
        : null;
}

function messageCardExecutionStrip(message, compact = false) {
    const type = (message?.message_type || "").toLowerCase();
    if (!isWorkerOutcomeMessageType(type)) {
        return null;
    }
    const metadata = message?.metadata || {};
    const worker = messageCardWorkerLabel(metadata);
    const stateLine = messageCardCurrentState(metadata);
    if (!worker && !stateLine) {
        return null;
    }
    const executionStatus = firstNonBlank(
        metadata.execution_status,
        metadata.executionStatus,
        metadata.worker_execution_status,
        metadata.workerExecutionStatus,
        metadata.task_status,
        metadata.taskStatus
    ).toLowerCase();
    const label = executionStatus === "partial_timeout"
        ? "部分结果"
        : (["active", "running", "in_progress"].includes(executionStatus) ? "执行中" : "最近执行");
    const previewText = messageCardWorkerOutcomePreview(message, compact ? 100 : 140);
    const title = worker ? `worker ${worker}` : stateLine;
    const detail = [
        worker && stateLine ? stateLine : "",
        compact ? "" : previewText
    ].filter(Boolean).join(" · ");
    return title || detail
        ? { label, title, detail, summary: [title, detail].filter(Boolean).join(" · ") }
        : null;
}

function buildAssistantMessage(task, flow) {
    const activeContext = flow?.runtime_context?.active_context || {};
    const taskMetadata = flow?.task?.metadata || task?.metadata || {};
    const latestNarrative = latestTaskOutcomeNarrative(task, flow, 320);
    const failureFallback = failureNarrativeFallback(taskMetadata);
    const preferredNarrative = failureFallback && looksLikeTerseOutcomeNarrative(latestNarrative)
        ? ""
        : latestNarrative;
    return preview(
        firstNonBlank(
            preferredNarrative,
            failureFallback,
            activeContext.continuity_summary,
            activeContext.continuitySummary,
            task?.summary,
            task?.next_step,
            task?.nextStep,
            "任务已进入 harness，等待继续推进。"
        ),
        320
    );
}

test("selected task thread bubble prefers latest task outcome narrative over terse continuity summary", () => {
    const task = {
        id: "task_demo",
        summary: "failed",
        next_step: "Inspect failure trace and decide whether to retry or handoff manually."
    };
    const flow = {
        runtime_context: {
            active_context: {
                continuity_summary: "failed"
            }
        },
        related_messages: [
            {
                id: "msg_1",
                task_id: "task_demo",
                message_type: "task_progress",
                created_at: 1778680176.4381566,
                content: "任务《继续看看 文档，规划下一步》已完成一轮推进。进展：failed。下一步：Inspect failure trace and decide whether to retry or handoff manually.。当前：waiting_human / human_gate · worker claude。",
                metadata: {
                    full_content: "failed\n\nWorker Output\n\n\nArtifact Content\n\n\n下一步\nInspect failure trace and decide whether to retry or handoff manually.",
                    next_step: "Inspect failure trace and decide whether to retry or handoff manually."
                }
            }
        ]
    };

    const message = buildAssistantMessage(task, flow);
    assert.equal(message.includes("已完成一轮推进"), true);
    assert.notEqual(message, "failed");
});

test("selected task thread bubble prefers focused task failure fallback over stale noisy task summary", () => {
    const task = {
        id: "task_demo",
        summary: "����: û���ҵ����� \"19120\"�� docs\\ARCHITECTURE.md 我会先把失败链梳理出来",
        next_step: "Inspect failure trace and decide whether to retry or handoff manually."
    };
    const flow = {
        task: {
            id: "task_demo",
            metadata: {
                previous_worker: "claude",
                failure_summary_readable: "���: û���ҵ����� \"19120\"��\nD:\\gitAll\\agent-cloud-harness\\docs\\ARCHITECTURE.md\n我会先把失败链梳理出来"
            }
        },
        runtime_context: {
            active_context: {
                continuity_summary: "failed"
            }
        },
        related_messages: []
    };

    const message = buildAssistantMessage(task, flow);
    assert.equal(message.includes("worker claude failed: thread not found (19120)"), true);
    assert.equal(message.includes("ARCHITECTURE"), false);
    assert.equal(message.includes("我会先把"), false);
});

test("selected task thread bubble prefers live failure summary over terse stale outcome narrative", () => {
    const task = {
        id: "task_demo",
        summary: "failed",
        next_step: "Inspect failure trace."
    };
    const flow = {
        task: {
            id: "task_demo",
            metadata: {
                previous_worker: "claude",
                failure_summary_readable: "���: û���ҵ����� \"19120\"��\nD:\\gitAll\\agent-cloud-harness\\docs\\ARCHITECTURE.md"
            }
        },
        runtime_context: {
            active_context: {
                continuity_summary: "failed"
            }
        },
        related_messages: [
            {
                id: "msg_1",
                task_id: "task_demo",
                message_type: "task_progress",
                created_at: 1778680176.4381566,
                content: "任务《demo》已完成一轮推进。进展：failed。下一步：Inspect failure trace.。当前：waiting_human / human_gate · worker claude。"
            }
        ]
    };

    const message = buildAssistantMessage(task, flow);
    assert.equal(message.includes("worker claude failed: thread not found (19120)"), true);
    assert.equal(message.includes("已完成一轮推进"), false);
    assert.equal(message.includes("ARCHITECTURE"), false);
});

test("selected task thread preview falls back to live flow failure summary when latest outcome full content is a stale shell", () => {
    const task = {
        id: "task_demo",
        summary: "failed",
        next_step: "Inspect failure trace and decide whether to retry or handoff manually."
    };
    const flow = {
        task: {
            metadata: {
                failure_summary_readable: "worker claude 返回了可读失败摘要",
                failure_class: "worker_runtime_transient",
                recovery_stage: "human_gate_required",
                auto_same_worker_retry_count: 1,
                auto_handoff_count: 1,
                auto_handoff_target: "deepseek"
            }
        },
        related_messages: [
            {
                id: "msg_1",
                task_id: "task_demo",
                message_type: "task_progress",
                created_at: 1778680176.4381566,
                content: "任务《继续看看 文档，规划下一步》已完成一轮推进。进展：failed。下一步：Inspect failure trace and decide whether to retry or handoff manually.。当前：waiting_human / human_gate · worker claude。",
                metadata: {
                    full_content: "failed\n\nWorker Output\n\n\nArtifact Content\n\n\n下一步\nInspect failure trace and decide whether to retry or handoff manually.",
                    next_step: "Inspect failure trace and decide whether to retry or handoff manually."
                }
            }
        ]
    };

    const narrative = latestTaskOutcomeNarrative(task, flow);
    assert.equal(narrative.includes("已完成一轮推进"), true);
    assert.equal(narrative.includes("worker claude 返回了可读失败摘要"), false);

    const fallback = failureNarrativeFallback(flow.task.metadata);
    assert.equal(fallback.includes("worker claude 返回了可读失败摘要"), true);
    assert.equal(fallback.includes("等待人工确认"), true);

    const fullContent = effectiveTaskOutcomeFullContent(task, flow);
    assert.equal(fullContent.includes("worker claude 返回了可读失败摘要"), true);
    assert.equal(fullContent.includes("下一步"), true);
    assert.equal(fullContent.includes("Worker Output"), false);
});

test("historical noisy failure summary is compressed into a readable short failure summary", () => {
    const task = {
        id: "task_demo"
    };
    const flow = {
        task: {
            metadata: {
                previous_worker: "claude",
                failure_summary_readable: "���: û���ҵ����� \"19120\"��\nD:\\gitAll\\agent-cloud-harness\\docs\\ARCHITECTURE.md\n我会先把失败链梳理出来\nstartup remote plugin sync failed; will retry on next app-server start error=chatgpt authentication required to sync"
            }
        }
    };

    const fallback = failureNarrativeFallback(flow.task.metadata);
    assert.equal(fallback.includes("worker claude failed: thread not found (19120)"), true);
    assert.equal(fallback.includes("ARCHITECTURE"), false);
    assert.equal(fallback.includes("我会先把"), false);
    assert.equal(fallback.includes("authentication required"), false);

    const previewText = assistantOutputPreview(task, flow, 260);
    assert.equal(previewText.includes("worker claude failed: thread not found (19120)"), true);
});

test("task progress transcript card exposes worker and short outcome preview before expansion", () => {
    const message = {
        message_type: "task_progress",
        content: "failed",
        metadata: {
            assigned_worker: "claude",
            task_status: "waiting_human",
            control_node: "human_gate",
            failure_summary_readable: "���: û���ҵ����� \"19120\"��\nD:\\gitAll\\agent-cloud-harness\\docs\\ARCHITECTURE.md"
        }
    };

    const executionStrip = messageCardExecutionStrip(message, false);
    assert.equal(executionStrip.label, "最近执行");
    assert.equal(executionStrip.title.includes("worker claude"), true);
    assert.equal(executionStrip.detail.includes("waiting_human / human_gate"), true);
    assert.equal(executionStrip.detail.includes("thread not found (19120)"), true);
    assert.equal(executionStrip.detail.includes("ARCHITECTURE"), false);

    const outcomeStrip = messageCardOutcomeStrip(message, false);
    assert.equal(outcomeStrip.label, "最近输出");
    assert.equal(outcomeStrip.title.includes("thread not found (19120)"), true);
    assert.equal(outcomeStrip.detail.includes("waiting_human / human_gate"), true);
});

test("worker round transcript card exposes execution and compact round output before expansion", () => {
    const message = {
        message_type: "worker_round",
        content: "Codex 执行了一轮，状态 partial_timeout，已产出部分结果。",
        metadata: {
            worker_id: "codex",
            execution_status: "partial_timeout",
            output_preview: "已修改 app.js，但测试还没跑完。",
            partial_output_chars: 640,
            partial_timeout_min_output_chars: 200
        }
    };

    const executionStrip = messageCardExecutionStrip(message, false);
    assert.equal(executionStrip.label, "部分结果");
    assert.equal(executionStrip.title, "worker codex");
    assert.equal(executionStrip.detail.includes("已修改 app.js"), true);

    const outcomeStrip = messageCardOutcomeStrip(message, false);
    assert.equal(outcomeStrip.label, "执行回合");
    assert.equal(outcomeStrip.title.includes("已修改 app.js"), true);
});

test("focused task transcript card prefers projected outcome preview over stale failed summary", () => {
    const message = {
        message_type: "task_progress",
        task_id: "task_demo",
        content: "任务《demo》已完成一轮推进。进展：failed。",
        metadata: {
            assigned_worker: "claude",
            selected_worker: "claude",
            task_status: "waiting_human",
            control_node: "human_gate",
            summary_preview: "failed",
            full_content: "failed\n\nWorker Output\n\n\nArtifact Content\n\n\n下一步\nInspect failure trace.",
            next_step: "Inspect failure trace."
        }
    };
    const flow = {
        task: {
            id: "task_demo",
            metadata: {
                assigned_worker: "claude",
                previous_worker: "claude",
                failure_summary_readable: "���: û���ҵ����� \"19120\"��\nD:\\gitAll\\agent-cloud-harness\\docs\\ARCHITECTURE.md",
                failure_class: "worker_runtime_transient",
                recovery_stage: "human_gate_required",
                auto_same_worker_retry_count: 1,
                auto_handoff_count: 1,
                auto_handoff_target: "deepseek"
            }
        },
        related_messages: [message]
    };

    const preview = assistantOutputPreview(flow.task, flow, 260);
    assert.equal(preview.includes("worker claude failed: thread not found (19120)"), true);
    assert.equal(preview.includes("ARCHITECTURE"), false);

    const projectedFullContent = effectiveTaskOutcomeFullContent(flow.task, flow);
    assert.equal(projectedFullContent.includes("thread not found (19120)"), true);
    assert.equal(projectedFullContent.includes("下一步"), true);
    assert.equal(projectedFullContent.includes("Worker Output"), false);
});

test("focused task transcript projection only needs live flow task identity, not selected task id", () => {
    const message = {
        message_type: "task_progress",
        task_id: "task_demo",
        content: "failed",
        metadata: {
            summary_preview: "failed",
            full_content: "failed\n\nWorker Output\n\n\nArtifact Content\n\n\n下一步\nInspect failure trace."
        }
    };
    const flow = {
        task: {
            id: "task_demo",
            metadata: {
                previous_worker: "claude",
                failure_summary_readable: "���: û���ҵ����� \"19120\"��\nD:\\gitAll\\agent-cloud-harness\\docs\\ARCHITECTURE.md"
            }
        },
        related_messages: [message]
    };

    const projected = buildMessageDisplayViewForFocusedFlow(message, flow);
    const outcomeStrip = messageCardOutcomeStrip(projected, false);

    assert.equal(projected.metadata.summary_preview.includes("worker claude failed: thread not found (19120)"), true);
    assert.equal(projected.metadata.summary_preview.includes("ARCHITECTURE"), false);
    assert.equal(projected.metadata.full_content.includes("thread not found (19120)"), true);
    assert.equal(projected.metadata.full_content.includes("Worker Output"), false);
    assert.equal(outcomeStrip.title.includes("thread not found (19120)"), true);
});

test("selected task gets a pinned latest-round output summary before message list history", () => {
    const task = {
        id: "task_demo",
        title: "继续看看 文档，规划下一步",
        status: "waiting_human",
        control_node: "human_gate",
        assigned_worker: "claude"
    };
    const flow = {
        task: {
            id: "task_demo",
            metadata: {
                assigned_worker: "claude",
                previous_worker: "claude",
                failure_summary_readable: "���: û���ҵ����� \"19120\"��",
                failure_class: "worker_runtime_transient",
                recovery_stage: "human_gate_required",
                auto_same_worker_retry_count: 1,
                auto_handoff_count: 1,
                auto_handoff_target: "deepseek"
            }
        },
        related_messages: [
            {
                id: "msg_progress",
                task_id: "task_demo",
                message_type: "task_progress",
                created_at: 1778680176.4381566,
                content: "failed",
                metadata: {
                    summary_preview: "failed"
                }
            }
        ]
    };

    const pinned = renderPinnedTaskOutcomeSummary(task, flow);
    assert.ok(pinned);
    assert.equal(pinned.executionStrip.label, "最近执行");
    assert.equal(pinned.executionStrip.title.includes("worker claude"), true);
    assert.equal(pinned.executionStrip.detail.includes("waiting_human / human_gate"), true);
    assert.equal(pinned.outcomeStrip.label, "最近输出");
    assert.equal(pinned.outcomeStrip.title.includes("thread not found (19120)"), true);
    assert.equal(pinned.showBody, false);
    assert.equal(flow.task.metadata.failure_class, "worker_runtime_transient");
    const compactPreview = pinnedTaskOutcomePreview(task, flow, 240);
    assert.equal(compactPreview.includes("thread not found (19120)"), true);
    assert.equal(compactPreview.includes("human_gate_required"), false);
    assert.equal(compactPreview.includes("下一步"), false);
    assert.equal(compactPreview.includes("恢复状态"), false);
    assert.equal(pinned.detail.includes("等待人工确认"), true);
    assert.equal(pinned.detail.includes("临时运行失败"), true);
    assert.equal(pinned.detail.includes("重试 1"), true);
    assert.equal(pinned.detail.includes("移交 1 -> deepseek"), true);
    assert.equal(pinned.detail.includes("retry 1"), false);
    assert.equal(pinned.detail.includes("handoff 1"), false);
    assert.equal(pinned.detail.includes("failure ·"), false);
    assert.equal(pinned.detail.includes("recovery ·"), false);
});

test("active worker label prefers current assigned worker over provider name after auto handoff", () => {
    const task = {
        id: "task_demo",
        assigned_worker: "openclaw-native",
        status: "active",
        control_node: "scheduler"
    };
    const flow = {
        task: {
            id: "task_demo",
            assigned_worker: "openclaw-native",
            metadata: {
                assigned_worker: "openclaw-native",
                previous_worker: "codex",
                failure_class: "worker_runtime_transient",
                recovery_stage: "auto_handoff_scheduled",
                auto_handoff_target: "openclaw-native"
            }
        },
        provider_selection: {
            selected_provider: "openclaw",
            selected_worker_id: "openclaw-native"
        },
        route_preview: {
            selected_worker: "openclaw-native"
        }
    };

    const workerLabel = activeWorkerLabel(task, flow);
    assert.equal(workerLabel, "openclaw-native");

    const executionStrip = buildThreadExecutionStrip(task, flow, workerLabel);
    assert.equal(executionStrip.label, "待继续");
    assert.equal(executionStrip.title, "worker openclaw-native");
});

test("human gate recovery detail explains next action for partial-result risk", () => {
    const detail = messageCardRecoveryDetail({
        failure_class: "partial_result_or_quality_risk",
        recovery_stage: "human_gate_required",
        manual_handoff_candidate: "deepseek"
    }, true);

    assert.equal(detail.includes("部分结果待确认"), true);
    assert.equal(detail.includes("等待人工确认"), true);
    assert.equal(detail.includes("先复核已有结果"), true);
    assert.equal(detail.includes("建议 · 先复核已有结果"), true);
    assert.equal(detail.includes("建议移交 -> deepseek"), true);
    assert.equal(detail.includes("manual handoff candidate -> deepseek"), false);
    assert.equal(detail.includes("handoff 1"), false);
    assert.equal(detail.includes("partial_result_or_quality_risk"), false);
    assert.equal(detail.includes("human_gate_required"), false);
});


test("human gate recovery detail explains environment fix path for environment-blocked failures", () => {
    const detail = messageCardRecoveryDetail({
        failure_class: "task_environment_blocked",
        recovery_stage: "human_gate_required"
    }, true);

    assert.equal(detail.includes("环境阻塞"), true);
    assert.equal(detail.includes("等待人工确认"), true);
    assert.equal(detail.includes("先修环境后继续"), true);
    assert.equal(detail.includes("建议 · 先修环境后继续"), true);
});

test("cold-start recovery detail shows fresh session hint", () => {
    const detail = messageCardRecoveryDetail({
        failure_class: "worker_runtime_transient",
        recovery_stage: "auto_handoff_scheduled",
        recovery_execution_mode: "fresh_session"
    }, true);

    assert.equal(detail.includes("自动切换 worker"), true);
    assert.equal(detail.includes("恢复 · fresh session"), true);
});
