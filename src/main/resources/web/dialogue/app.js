import { buildComposerSubmissionPlan } from "./composer-plan.js";
import { buildMessageSignalPlan } from "./message-card-plan.js";
import { buildFacadeReplyFeedback } from "./facade-reply-plan.js";
import { scopedFacadeReply } from "./facade-reply-scope.js";
import { buildMessageRoleSummary } from "./message-summary-plan.js";
import { buildMessageSummaryStackPlan } from "./message-summary-stack-plan.js";
import { renderMessageSummaryStackHtml } from "./message-summary-render-plan.js";
import { buildMessageExpansionPlan, hasExpandedTaskOutcomeContent } from "./message-expansion-plan.js";
import { buildJudgmentCardBody } from "./judgment-card-plan.js";
import { buildRouteBoxPlan } from "./route-box-plan.js";
import { buildProviderDeprioritizationPlan } from "./provider-deprioritization-plan.js";
import { buildExperimentSummaryPlan } from "./experiment-summary-plan.js";
import { buildRelatedMessagesPlan } from "./related-messages-plan.js";
import { buildContinuitySummaryPlan } from "./continuity-summary-plan.js";
import { buildFacadeReplyHighlightPlan } from "./facade-reply-highlight-plan.js";
import { buildChainContextPlan } from "./chain-context-plan.js";
import { renderChainContextListHtml } from "./chain-context-render-plan.js";
import { buildTaskActionPlan } from "./task-action-plan.js";
import { renderTaskActionHtml } from "./task-action-render-plan.js";
import { buildTaskOverviewPlan } from "./task-overview-plan.js";
import { renderTaskHeaderHtml } from "./task-header-render-plan.js";
import { buildRecoveryJobPlan } from "./recovery-job-plan.js";
import { buildToolTraceStatusLabel, buildToolTraceSummary } from "./tool-trace-plan.js";
import { renderComposerInlineSignalsHtml } from "./composer-inline-render-plan.js";
import { renderFacadeReplyBadgeHtml } from "./facade-reply-badge-render-plan.js";
import { buildExecutionBoundaryFacts } from "./execution-boundary-plan.js";
import { buildProviderRunFilePlan } from "./provider-run-file-plan.js";
import { buildAgentActionPlan } from "./agent-action-plan.js";
import { buildPendingFacadeReply } from "./facade-pending-plan.js";
import { buildPendingAutoTaskTracker, resolvePendingAutoTaskCandidate } from "./pending-auto-task-plan.js";
import { buildComposerSubmitContext } from "./composer-submit-context-plan.js";
import { buildFacadeRequest } from "./composer-request-plan.js";
import { requestFacadeCompletion } from "./facade-client-plan.js";
import { reconcileTaskSelection } from "./task-selection-plan.js";
import { isTrueFlag } from "./mounted-object-plan.js";
import {
    readFacadeSurfaceFromHash,
    facadeSurfaceSummaryLabel,
    writeFacadeSurfaceToParams
} from "./facade-surface-plan.js";

const state = {
    sessions: [],
    tasks: [],
    messages: [],
    relatedMessages: [],
    expandedMessageIds: new Set(),
    expandedThreadOutputTaskIds: new Set(),
    messageFilterRole: "all",
    messageFilterScope: "all",
    taskStatusFilter: "all",
    composerMode: "auto",
    sidebarOpen: true,
    detailsOpen: true,
    workers: [],
    selectedSessionId: null,
    selectedTaskId: null,
    selectedTaskStickyUntil: 0,
    followupParentTaskId: null,
    pendingAutoTaskTracker: null,
    lastFacadeReply: null,
    liveFlow: null,
    recoveryJobs: [],
    agentActions: [],
    experimentSummary: null,
    toastTimer: null,
    pollingTimer: null,
    facadeSurface: "chat_completions",
    selectedTaskLoading: false
};

const dom = {
    healthBadge: document.getElementById("healthBadge"),
    sessionCount: document.getElementById("sessionCount"),
    taskCount: document.getElementById("taskCount"),
    chainCount: document.getElementById("chainCount"),
    selectedStatus: document.getElementById("selectedStatus"),
    sessionSidebar: document.getElementById("sessionSidebar"),
    sidebarToggle: document.getElementById("sidebarToggle"),
    sidebarBackdrop: document.getElementById("sidebarBackdrop"),
    sessionList: document.getElementById("sessionList"),
    sessionForm: document.getElementById("sessionForm"),
    sessionTitle: document.getElementById("sessionTitle"),
    refreshSessionsButton: document.getElementById("refreshSessionsButton"),
    heroTitle: document.getElementById("heroTitle"),
    heroSubtitle: document.getElementById("heroSubtitle"),
    workspaceSurfaceTitle: document.getElementById("workspaceSurfaceTitle"),
    messagePanelHint: document.getElementById("messagePanelHint"),
    messageRoleFilters: document.getElementById("messageRoleFilters"),
    messageScopeFilters: document.getElementById("messageScopeFilters"),
    messageSummary: document.getElementById("messageSummary"),
    messageList: document.getElementById("messageList"),
    threadDrawer: document.getElementById("threadDrawer"),
    threadDrawerMeta: document.getElementById("threadDrawerMeta"),
    embeddedThreadPanel: document.getElementById("embeddedThreadPanel"),
    clearMessageButton: document.getElementById("clearMessageButton"),
    composerModeSwitch: document.getElementById("composerModeSwitch"),
    composerAdvanced: document.getElementById("composerAdvanced"),
    threadHint: document.getElementById("threadHint"),
    taskStatusFilters: document.getElementById("taskStatusFilters"),
    refreshThreadButton: document.getElementById("refreshThreadButton"),
    taskThread: document.getElementById("taskThread"),
    taskForm: document.getElementById("taskForm"),
    taskTitle: document.getElementById("taskTitle"),
    taskType: document.getElementById("taskType"),
    taskPriority: document.getElementById("taskPriority"),
    taskAssignedWorker: document.getElementById("taskAssignedWorker"),
    taskModelMode: document.getElementById("taskModelMode"),
    taskGoal: document.getElementById("taskGoal"),
    taskLocalPaths: document.getElementById("taskLocalPaths"),
    taskValidationCommands: document.getElementById("taskValidationCommands"),
    taskExecutionContract: document.getElementById("taskExecutionContract"),
    taskAutoStart: document.getElementById("taskAutoStart"),
    taskContinueCurrent: document.getElementById("taskContinueCurrent"),
    taskAutoMultiRound: document.getElementById("taskAutoMultiRound"),
    taskIntent: document.getElementById("taskIntent"),
    submitTaskButton: document.getElementById("submitTaskButton"),
    composerSessionLabel: document.getElementById("composerSessionLabel"),
    composerContextBlock: document.getElementById("composerContextBlock"),
    composerInlineState: document.getElementById("composerInlineState"),
    composerModeHint: document.getElementById("composerModeHint"),
    composerTaskHint: document.getElementById("composerTaskHint"),
    composerRoutingMeta: document.getElementById("composerRoutingMeta"),
    composerRecovery: document.getElementById("composerRecovery"),
    messageHint: document.getElementById("messageHint"),
    messageAttachTaskWrap: document.getElementById("messageAttachTaskWrap"),
    messageAttachTask: document.getElementById("messageAttachTask"),
    followupButton: document.getElementById("followupButton"),
    clearFollowupButton: document.getElementById("clearFollowupButton"),
    detailsToggleButton: document.getElementById("detailsToggleButton"),
    taskDetailsPanel: document.getElementById("taskDetailsPanel"),
    detailsCloseButton: document.getElementById("detailsCloseButton"),
    detailTitle: document.getElementById("detailTitle"),
    taskDetailsEmpty: document.getElementById("taskDetailsEmpty"),
    taskOverview: document.getElementById("taskOverview"),
    taskActions: document.getElementById("taskActions"),
    taskSecondaryActions: document.getElementById("taskSecondaryActions"),
    taskActionDrawer: document.getElementById("taskActionDrawer"),
    taskDetailsScroll: document.getElementById("taskDetailsScroll"),
    handoffWorker: document.getElementById("handoffWorker"),
    handoffButton: document.getElementById("handoffButton"),
    chainContext: document.getElementById("chainContext"),
    continuitySummary: document.getElementById("continuitySummary"),
    continuityChips: document.getElementById("continuityChips"),
    mountedContext: document.getElementById("mountedContext"),
    routeBox: document.getElementById("routeBox"),
    experimentSummary: document.getElementById("experimentSummary"),
    decisionList: document.getElementById("decisionList"),
    artifactList: document.getElementById("artifactList"),
    agentActionList: document.getElementById("agentActionList"),
    relatedMessages: document.getElementById("relatedMessages"),
    toolList: document.getElementById("toolList"),
    toast: document.getElementById("toast"),
    taskDetailModal: document.getElementById("taskDetailModal"),
    modalTitle: document.getElementById("modalTitle"),
    modalBody: document.getElementById("modalBody"),
    modalTaskInfo: document.getElementById("modalTaskInfo"),
    modalFullContent: document.getElementById("modalFullContent"),
    modalRouteInfo: document.getElementById("modalRouteInfo"),
    modalDecisions: document.getElementById("modalDecisions"),
    modalTools: document.getElementById("modalTools"),
    modalProviderRunFiles: document.getElementById("modalProviderRunFiles"),
    modalCloseButton: document.getElementById("modalCloseButton"),
    modalCloseBtn: document.getElementById("modalCloseBtn")
};

document.addEventListener("DOMContentLoaded", () => {
    bindEvents();
    init().catch((error) => {
        console.error(error);
        showToast(error.message || "dialogue init failed", true);
    });
});

function bindEvents() {
    dom.sessionForm.addEventListener("submit", (event) => {
        onCreateSession(event).catch(handleError);
    });
    dom.refreshSessionsButton.addEventListener("click", () => refreshAll(true));
    dom.sidebarToggle.addEventListener("click", toggleSidebar);
    dom.sidebarBackdrop.addEventListener("click", () => setSidebarOpen(false));
    dom.detailsToggleButton.addEventListener("click", toggleDetailsPanel);
    dom.detailsCloseButton.addEventListener("click", () => setDetailsOpen(false));
    dom.refreshThreadButton.addEventListener("click", () => {
        if (state.selectedTaskId) {
            loadSelectedTask(state.selectedTaskId, true).catch(handleError);
            return;
        }
        refreshAll(true).catch(handleError);
    });
    dom.composerModeSwitch.addEventListener("click", onComposerModeClick);
    dom.composerAdvanced.addEventListener("toggle", onComposerAdvancedToggle);
    dom.clearMessageButton.addEventListener("click", onClearComposerInput);
    dom.composerRecovery.addEventListener("click", (event) => {
        onComposerRecoveryClick(event).catch(handleError);
    });
    dom.messageRoleFilters.addEventListener("click", onMessageFilterClick);
    dom.messageScopeFilters.addEventListener("click", onMessageFilterClick);
    dom.taskStatusFilters.addEventListener("click", onTaskStatusFilterClick);
    dom.messageList.addEventListener("click", onMessageActionClick);
    dom.relatedMessages.addEventListener("click", onMessageActionClick);
    dom.taskForm.addEventListener("submit", (event) => {
        onCreateTask(event).catch(handleError);
    });
    dom.taskIntent.addEventListener("keydown", onComposerKeydown);
    [
        dom.taskTitle,
        dom.taskGoal,
        dom.taskType,
        dom.taskPriority,
        dom.taskAssignedWorker,
        dom.taskModelMode,
        dom.taskAutoStart,
        dom.taskContinueCurrent,
        dom.taskAutoMultiRound
    ].forEach((element) => {
        if (!element) {
            return;
        }
        const eventName = element.tagName === "INPUT" && element.type !== "checkbox" ? "input" : "change";
        element.addEventListener(eventName, onComposerMetadataChange);
    });
    dom.taskThread.addEventListener("click", onThreadClick);
    dom.taskThread.addEventListener("keydown", onThreadKeydown);
    dom.chainContext.addEventListener("click", onChainContextClick);
    dom.taskActions.addEventListener("click", (event) => {
        onTaskActionClick(event).catch(handleError);
    });
    dom.taskSecondaryActions.addEventListener("click", (event) => {
        onTaskActionClick(event).catch(handleError);
    });
    if (dom.followupButton) {
        dom.followupButton.addEventListener("click", onFollowupDraft);
    }
    if (dom.clearFollowupButton) {
        dom.clearFollowupButton.addEventListener("click", onClearFollowup);
    }
    dom.handoffButton.addEventListener("click", () => {
        onHandoff().catch(handleError);
    });
    dom.modalCloseButton.addEventListener("click", closeModal);
    dom.modalCloseBtn.addEventListener("click", closeModal);
    if (dom.modalProviderRunFiles) {
        dom.modalProviderRunFiles.addEventListener("click", (event) => {
            onProviderRunFileClick(event).catch(handleError);
        });
    }
    dom.taskDetailModal.addEventListener("click", (e) => {
        if (e.target === dom.taskDetailModal) {
            closeModal();
        }
    });
    window.addEventListener("hashchange", () => {
        applyLocationSelection();
        refreshAll(false).catch(handleError);
    });
}

async function init() {
    applyLocationSelection();
    syncSidebarForViewport();
    renderDetailsPanelState();
    await Promise.all([loadHealth(), loadWorkers()]);
    await refreshAll(false);
    startPolling();
    window.addEventListener("resize", onViewportResize);
}

async function refreshAll(loud) {
    await loadSessions();
    await loadTasks();
    if (state.selectedTaskId) {
        await loadSelectedTask(state.selectedTaskId, false);
        await loadMessages(taskSessionId(selectedTask()) || state.selectedSessionId);
    } else {
        await loadMessages();
        state.liveFlow = null;
        state.experimentSummary = null;
        state.relatedMessages = [];
        renderMessages();
        renderThread();
        renderDetails();
    }
    if (loud) {
        showToast("会话与任务已刷新");
    }
}

async function loadHealth() {
    const health = await api("/api/v1/health");
    dom.healthBadge.dataset.state = health.status === "up" ? "up" : "down";
    dom.healthBadge.textContent = health.status === "up" ? `healthy · v${health.version}` : "down";
}

async function loadWorkers() {
    state.workers = await api("/api/v1/workers");
    renderWorkerOptions();
}

async function loadSessions() {
    const sessions = await api("/api/v1/sessions");
    state.sessions = sessions
        .slice()
        .sort((a, b) => timestampMs(b.updated_at || b.updatedAt || 0) - timestampMs(a.updated_at || a.updatedAt || 0));

    if (!state.selectedSessionId || !state.sessions.some((session) => session.id === state.selectedSessionId)) {
        state.selectedSessionId = state.sessions[0]?.id ?? null;
    }

    dom.sessionCount.textContent = String(state.sessions.length);
    const currentSession = state.sessions.find((session) => session.id === state.selectedSessionId);
    dom.composerSessionLabel.textContent = currentSession?.title || "自动创建";
    dom.heroTitle.textContent = currentSession
        ? currentSession.title
        : "继续当前 thread";
    dom.heroSubtitle.textContent = currentSession
        ? "先继续 transcript；task 细节和诊断都按需展开。"
        : "先创建一个 thread，或者直接发出第一条消息，让系统自动创建 session。";
    renderSessions();
    renderComposerContext();
    renderMessageComposerContext();
}

async function loadTasks() {
    if (state.selectedSessionId) {
        state.tasks = await api(`/api/v1/sessions/${encodeURIComponent(state.selectedSessionId)}/tasks`);
    } else {
        state.tasks = await api("/api/v1/tasks");
    }

    state.tasks = state.tasks
        .slice()
        .sort((a, b) => timestampMs(a.created_at || a.createdAt || 0) - timestampMs(b.created_at || b.createdAt || 0));

    // 用户刚显式选中过 manual-start task 时，优先保住这条选中态，
    // 避免旧 auto-start task 的晚到 progress/result 刷新把它抢回去。
    const selectedTaskStillExists = state.selectedTaskId
        && state.tasks.some((task) => task.id === state.selectedTaskId);
    const hashTaskId = currentHashTaskId();
    const preserveHashSelection = selectedTaskStillExists && hashTaskId === state.selectedTaskId;
    const preserveExplicitSelection = selectedTaskStillExists
        && (preserveHashSelection || Date.now() < Number(state.selectedTaskStickyUntil || 0));

    if (preserveExplicitSelection) {
        if (!state.tasks.some((task) => task.id === state.followupParentTaskId)) {
            state.followupParentTaskId = null;
        }
        if (dom.taskContinueCurrent.checked && !state.tasks.some((task) => task.id === state.selectedTaskId)) {
            dom.taskContinueCurrent.checked = false;
        }
        renderThread();
        renderComposerContext();
        renderMessageComposerContext();
        syncLocationSelection();
        return;
    }

    const taskSelection = reconcileTaskSelection({
        tasks: state.tasks,
        selectedTaskId: state.selectedTaskId,
        currentSessionId: state.selectedSessionId,
        liveFlowTaskId: firstNonBlank(state.liveFlow?.task?.id),
        liveFlowSessionId: taskSessionId(state.liveFlow?.task),
        facadeReplyTaskId: state.lastFacadeReply?.taskId || "",
        facadeReplySessionId: state.lastFacadeReply?.sessionId || ""
    });
    state.selectedTaskId = taskSelection.selectedTaskId;
    const pendingAutoTaskId = resolvePendingAutoTaskCandidate({
        tracker: state.pendingAutoTaskTracker,
        currentSessionId: state.selectedSessionId,
        tasks: state.tasks
    });
    if (pendingAutoTaskId) {
        state.selectedTaskId = pendingAutoTaskId;
        state.pendingAutoTaskTracker = null;
    }
    if (!taskSelection.keepLiveFlow) {
        state.liveFlow = null;
        state.experimentSummary = null;
        state.relatedMessages = [];
    }
    if (state.followupParentTaskId && !state.tasks.some((task) => task.id === state.followupParentTaskId)) {
        state.followupParentTaskId = null;
    }
    if (dom.taskContinueCurrent.checked && !state.tasks.some((task) => task.id === state.selectedTaskId)) {
        dom.taskContinueCurrent.checked = false;
    }

    const chains = buildTaskChains(state.tasks);
    dom.taskCount.textContent = String(state.tasks.length);
    dom.chainCount.textContent = String(chains.length);
    dom.threadHint.textContent = state.selectedSessionId
        ? `当前展示 ${chains.length} 条迭代链 / ${state.tasks.length} 个任务。`
        : "当前未锁定 session，展示最近任务。";
    renderThread();
    renderComposerContext();
    renderMessageComposerContext();
    syncLocationSelection();
}

async function loadMessages(sessionId = state.selectedSessionId) {
    if (!sessionId) {
        state.messages = [];
        pruneExpandedMessageIds();
        renderMessages();
        renderMessageComposerContext();
        return;
    }

    const messages = await api(`/api/v1/sessions/${encodeURIComponent(sessionId)}/messages?limit=80`);
    state.messages = messages
        .slice()
        .sort((a, b) => timestampMs(a.created_at || a.createdAt || 0) - timestampMs(b.created_at || b.createdAt || 0));
    pruneExpandedMessageIds();
    renderMessages();
    renderMessageComposerContext();
}

async function loadRelatedMessages(task) {
    const taskId = task?.id;
    const sessionId = taskSessionId(task) || state.selectedSessionId;
    if (!taskId || !sessionId) {
        state.relatedMessages = [];
        pruneExpandedMessageIds();
        return;
    }

    const messages = await api(
        `/api/v1/sessions/${encodeURIComponent(sessionId)}/messages?limit=20&task_id=${encodeURIComponent(taskId)}`
    );
    state.relatedMessages = messages
        .slice()
        .sort((a, b) => timestampMs(b.created_at || b.createdAt || 0) - timestampMs(a.created_at || a.createdAt || 0));
    pruneExpandedMessageIds();
}

function applyRelatedMessagesFromLiveFlow(flow) {
    const messages = flow?.related_messages || flow?.relatedMessages;
    if (!Array.isArray(messages)) {
        return false;
    }
    state.relatedMessages = messages
        .slice()
        .sort((a, b) => timestampMs(b.created_at || b.createdAt || 0) - timestampMs(a.created_at || a.createdAt || 0));
    pruneExpandedMessageIds();
    return true;
}

async function loadSelectedTask(taskId, loud) {
    state.selectedTaskLoading = true;
    renderDetails();
    const encodedTaskId = encodeURIComponent(taskId);
    const [liveFlow, recoveryJobs, agentActions] = await Promise.all([
        api(`/api/v1/tasks/${encodedTaskId}/live_flow?limit=8`),
        apiOrNull(`/api/v1/tasks/${encodedTaskId}/recovery_jobs?limit=5`),
        apiOrNull(`/api/v1/agent_actions?task_id=${encodedTaskId}&limit=20`)
    ]);
    if (state.selectedTaskId !== taskId) {
        state.selectedTaskLoading = false;
        return;
    }
    const task = liveFlow?.task || null;
    const sessionId = taskSessionId(task);
    if (sessionId && sessionId !== state.selectedSessionId) {
        state.selectedSessionId = sessionId;
        await loadSessions();
        await loadTasks();
        await loadMessages(sessionId);
        if (state.selectedTaskId !== taskId) {
            state.selectedTaskLoading = false;
            return;
        }
    }

    state.liveFlow = liveFlow;
    if (task?.id) {
        state.tasks = state.tasks.map((item) => item?.id === task.id ? task : item);
    }
    state.recoveryJobs = Array.isArray(recoveryJobs) ? recoveryJobs : [];
    state.agentActions = Array.isArray(agentActions) ? agentActions : [];
    state.experimentSummary = await loadTaskExperimentSummary(taskId, liveFlow);
    if (state.selectedTaskId !== taskId) {
        state.selectedTaskLoading = false;
        return;
    }
    state.selectedTaskId = taskId;
    if (!applyRelatedMessagesFromLiveFlow(liveFlow)) {
        await loadRelatedMessages(task);
        if (state.selectedTaskId !== taskId) {
            state.selectedTaskLoading = false;
            return;
        }
    }
    state.selectedTaskLoading = false;
    renderMessages();
    renderThread();
    renderDetails();
    renderComposerContext();
    renderMessageComposerContext();
    syncLocationSelection();
    if (loud) {
        showToast(`已刷新任务 ${taskId}`);
    }
}

async function selectTask(taskId, loud = false) {
    state.selectedTaskId = taskId;
    state.selectedTaskStickyUntil = Date.now() + 15000;
    syncLocationSelection();
    await loadSelectedTask(taskId, loud);
}

async function onCreateSession(event) {
    event.preventDefault();
    const title = dom.sessionTitle.value.trim() || "untitled";
    const session = await api("/api/v1/sessions", {
        method: "POST",
        body: JSON.stringify({ title })
    });
    dom.sessionTitle.value = "";
    state.selectedSessionId = session.id;
    state.selectedTaskId = null;
    state.selectedTaskStickyUntil = 0;
    state.liveFlow = null;
    state.experimentSummary = null;
    state.relatedMessages = [];
    state.lastFacadeReply = null;
    state.followupParentTaskId = null;
    state.pendingAutoTaskTracker = null;
    dom.taskContinueCurrent.checked = false;
    syncLocationSelection();
    renderThread();
    renderDetails();
    renderComposerContext();
    renderMessageComposerContext();
    await loadSessions();
    await loadTasks();
    await loadMessages(session.id);
    renderSessions();
    if (window.innerWidth <= 1140) {
        setSidebarOpen(false);
    }
    showToast(`已创建会话 ${session.title || session.id}`);
}

async function createRecoverySession() {
    const seed = dom.taskTitle.value.trim() || dom.taskIntent.value.trim() || "继续当前对话";
    const session = await api("/api/v1/sessions", {
        method: "POST",
        body: JSON.stringify({ title: deriveTitle(seed) })
    });
    state.selectedSessionId = session.id;
    state.selectedTaskId = null;
    state.selectedTaskStickyUntil = 0;
    state.liveFlow = null;
    state.experimentSummary = null;
    state.relatedMessages = [];
    state.lastFacadeReply = null;
    state.followupParentTaskId = null;
    state.pendingAutoTaskTracker = null;
    dom.taskContinueCurrent.checked = false;
    syncLocationSelection();
    await loadSessions();
    await loadTasks();
    await loadMessages(session.id);
    renderSessions();
    renderMessages();
    renderThread();
    renderDetails();
    renderComposerContext();
    renderMessageComposerContext();
    if (window.innerWidth <= 1140) {
        setSidebarOpen(false);
    }
    showToast(`已切到新会话 ${session.title || session.id}`);
}

async function onCreateTask(event) {
    event.preventDefault();
    const intent = dom.taskIntent.value.trim();
    if (!intent) {
        showToast("请先填写任务说明", true);
        dom.taskIntent.focus();
        return;
    }

    const session = currentSession();
    if (isClosedSession(session)) {
        showToast("当前 session 已关闭，请先新建会话后再发布任务", true);
        return;
    }

    const submissionPlan = composerSubmissionPlan();
    const explicitSelectedTask = state.tasks.find((task) => task.id === state.selectedTaskId) || null;
    const submitContext = buildComposerSubmitContext({
        planResolvedMode: submissionPlan.resolvedMode,
        selectedTaskId: explicitSelectedTask?.id || state.selectedTaskId || "",
        selectedTaskStatus: explicitSelectedTask?.status || selectedTask()?.status || "",
        followupParentTaskId: state.followupParentTaskId,
        continueCurrentChecked: dom.taskContinueCurrent.checked
    });
    const busyLabel = submissionPlan.resolvedMode === "message" ? "发送中..." : "发布中...";
    try {
        setButtonBusy(dom.submitTaskButton, true, submitTaskButtonLabel(), busyLabel);
        const session = await ensureSessionForMessage(intent);
        const pendingReplyTaskId = submitContext.followupParentTaskId
            || submitContext.referencedTaskId
            || submitContext.continueCurrentTaskId;
        const pendingExistingTaskId = submitContext.referencedTaskId || submitContext.continueCurrentTaskId;
        const pendingReply = buildPendingFacadeReply({
            sessionId: session.id,
            taskId: pendingReplyTaskId,
            resolvedMode: submissionPlan.resolvedMode
        });
        state.pendingAutoTaskTracker = buildPendingAutoTaskTracker({
            sessionId: session.id,
            resolvedMode: submissionPlan.resolvedMode,
            existingTaskId: pendingExistingTaskId,
            intent,
            currentTaskIds: state.tasks.map((task) => task?.id || "")
        });
        if (pendingReply) {
        state.selectedSessionId = session.id;
        state.lastFacadeReply = pendingReply;
        renderComposerContext();
            renderMessageComposerContext();
        }
        const completionPromise = submitComposerThroughChatFacade(intent, submissionPlan, submitContext, session);
        await waitForPendingAutoTaskSelection(session.id);
        const completion = await completionPromise;
        await applyChatFacadeCompletion(completion, intent, submissionPlan);
        await waitForPendingAutoTaskSelection(session.id);
    } finally {
        setButtonBusy(dom.submitTaskButton, false, submitTaskButtonLabel(), busyLabel);
    }
}

function onClearComposerInput() {
    dom.taskIntent.value = "";
    dom.taskIntent.focus();
    showToast("已清空输入草稿");
}

function onComposerModeClick(event) {
    const button = event.target.closest("[data-composer-mode]");
    if (!button) {
        return;
    }
    const mode = button.dataset.composerMode;
    if (!mode || state.composerMode === mode) {
        return;
    }
    state.composerMode = mode;
    if (mode !== "auto") {
        state.followupParentTaskId = null;
    }
    renderComposerContext();
    renderMessageComposerContext();
}

function onComposerAdvancedToggle() {
    renderComposerContext();
    renderMessageComposerContext();
}

function onComposerMetadataChange() {
    renderComposerContext();
    renderMessageComposerContext();
}

async function onComposerRecoveryClick(event) {
    const button = event.target.closest("[data-composer-recovery]");
    if (!button) {
        return;
    }
    if (button.dataset.composerRecovery === "new-session") {
        await createRecoverySession();
    }
}

function onComposerKeydown(event) {
    if (!event || event.key !== "Enter") {
        return;
    }
    if (!event.ctrlKey && !event.metaKey) {
        return;
    }
    event.preventDefault();
    if (!dom.submitTaskButton || dom.submitTaskButton.disabled) {
        return;
    }
    dom.taskForm.requestSubmit();
}

function onMessageActionClick(event) {
    const button = event.target.closest("[data-message-action]");
    if (!button) {
        return;
    }

    const action = button.dataset.messageAction;
    
    if (action === "toggle-expand") {
        const card = button.closest(".message-card");
        const messageId = firstNonBlank(card?.dataset?.messageId, button.dataset.messageId);
        if (card && messageId) {
            const expanded = !card.classList.contains("message-card--expanded");
            setMessageExpanded(messageId, expanded);
            card.classList.toggle("message-card--expanded", expanded);
            const indicator = card.querySelector(".message-card__expand-label");
            if (indicator) {
                indicator.textContent = messageExpansionToggleLabel(messageById(messageId), expanded);
            }
        }
        return;
    }

    const message = messageById(button.dataset.messageId);
    if (!message) {
        showToast("消息不存在或已过期", true);
        return;
    }

    if (action === "use-draft") {
        applyMessageDraft(message);
        return;
    }

    if (action === "view-task") {
        const taskId = messageTaskId(message);
        if (!taskId) {
            showToast("这条消息没有关联 task", true);
            return;
        }
        selectTask(taskId, false).catch(handleError);
    }
}

async function onTaskActionClick(event) {
    const button = event.target.closest("[data-task-action]");
    const targetTaskId = activeThreadTaskId() || state.selectedTaskId;
    if (!button || !targetTaskId) {
        return;
    }

    state.selectedTaskId = targetTaskId;
    const action = button.dataset.taskAction;
    const body = action === "recover"
        ? JSON.stringify({ mode: "auto", reason: "manual recovery from dialogue" })
        : "{}";
    const actionPath = action === "recover" ? "recover?async=true" : action;
    const result = await api(`/api/v1/tasks/${encodeURIComponent(targetTaskId)}/${actionPath}`, {
        method: "POST",
        body
    });
    await loadTasks();
    state.selectedTaskId = targetTaskId;
    await loadSelectedTask(targetTaskId, false);
    await loadMessages(taskSessionId(selectedTask()) || state.selectedSessionId);
    const requestId = result?.request_id || result?.requestId;
    showToast(requestId ? `已触发 ${action}: ${requestId}` : `已执行 ${action}`);
}

function activeThreadTaskId() {
    return dom.taskThread
        ?.querySelector("[data-task-id].is-active")
        ?.getAttribute("data-task-id") || "";
}

async function onHandoff() {
    if (!state.selectedTaskId) {
        showToast("请先选择任务", true);
        return;
    }
    const targetWorker = dom.handoffWorker.value;
    if (!targetWorker) {
        showToast("请选择目标 worker", true);
        return;
    }

    await api(`/api/v1/tasks/${encodeURIComponent(state.selectedTaskId)}/handoff`, {
        method: "POST",
        body: JSON.stringify({ target_worker: targetWorker })
    });
    await loadTasks();
    await loadSelectedTask(state.selectedTaskId, false);
    await loadMessages(taskSessionId(selectedTask()) || state.selectedSessionId);
    showToast(`已移交到 ${targetWorker}`);
}

function onFollowupDraft() {
    const task = state.tasks.find((item) => item.id === state.selectedTaskId) || selectedTask();
    if (!task) {
        showToast("请先选择一个任务", true);
        return;
    }

    const draft = buildFollowupDraft(task, state.liveFlow);
    dom.taskTitle.value = draft.title;
    dom.taskGoal.value = draft.goal;
    dom.taskIntent.value = draft.intent;
    dom.taskType.value = draft.taskType;
    dom.taskPriority.value = draft.priority;
    dom.taskContinueCurrent.checked = false;
    state.followupParentTaskId = task.id;
    state.composerMode = "auto";
    renderComposerContext();
    renderMessageComposerContext();
    dom.taskIntent.focus();
    dom.taskIntent.setSelectionRange(dom.taskIntent.value.length, dom.taskIntent.value.length);
    showToast(`已生成 ${task.title || task.id} 的 follow-up 草稿`);
}

function onClearFollowup() {
    state.followupParentTaskId = null;
    dom.taskContinueCurrent.checked = false;
    renderComposerContext();
    renderMessageComposerContext();
    showToast("已清除 follow-up 关联");
}

let clickTimer = null;
function onThreadClick(event) {
    const outputToggle = event.target.closest("[data-thread-output-toggle]");
    if (outputToggle) {
        event.stopPropagation();
        const taskId = outputToggle.dataset.threadOutputToggle;
        if (!taskId) {
            return;
        }
        setThreadOutputExpanded(taskId, !isThreadOutputExpanded(taskId));
        renderThread();
        return;
    }
    const taskCard = event.target.closest("[data-task-id]");
    if (!taskCard) {
        return;
    }
    const taskId = taskCard.dataset.taskId;
    
    if (clickTimer) {
        clearTimeout(clickTimer);
        clickTimer = null;
        openTaskDetailModal(taskId).catch(handleError);
        return;
    }
    
    clickTimer = setTimeout(() => {
        clickTimer = null;
        selectTask(taskId, false).catch(handleError);
    }, 250);
}

function onThreadKeydown(event) {
    const taskCard = event.target.closest("[data-task-id]");
    if (!taskCard || (event.key !== "Enter" && event.key !== " ")) {
        return;
    }
    event.preventDefault();
    selectTask(taskCard.dataset.taskId, false).catch(handleError);
}

function onChainContextClick(event) {
    const target = event.target.closest("[data-chain-task-id]");
    if (!target) {
        return;
    }
    const taskId = target.dataset.chainTaskId;
    if (!taskId || taskId === state.selectedTaskId) {
        return;
    }
    selectTask(taskId, false).catch(handleError);
}

function renderSessions() {
    if (state.sessions.length === 0) {
        dom.sessionList.innerHTML = emptyState("还没有 thread。直接发送第一条消息也会自动创建。");
        return;
    }

    dom.sessionList.innerHTML = state.sessions.map((session) => {
        const active = session.id === state.selectedSessionId ? "is-active" : "";
        const summaryPreview = session.summary ? escapeHtml(session.summary).substring(0, 56) + (session.summary.length > 56 ? "..." : "") : "";
        const latestLabel = formatTime(session.updated_at || session.updatedAt);
        const title = session.title || session.id;
        return `
            <button class="session-card ${active}" data-session-id="${escapeHtml(session.id)}" type="button">
                <div class="session-card__eyebrow">
                    <span>${latestLabel}</span>
                </div>
                <div class="session-card__title">${escapeHtml(title)}</div>
                ${summaryPreview ? `<div class="session-card__preview">${summaryPreview}</div>` : ""}
            </button>
        `;
    }).join("");

    dom.sessionList.querySelectorAll("[data-session-id]").forEach((button) => {
        button.addEventListener("click", async () => {
            state.selectedSessionId = button.dataset.sessionId;
            state.followupParentTaskId = null;
            dom.taskContinueCurrent.checked = false;
            await loadSessions();
            await loadTasks();
            await loadMessages(state.selectedSessionId);
            if (state.tasks.length > 0) {
                await selectTask(state.tasks[state.tasks.length - 1].id, false);
            } else {
            state.selectedTaskId = null;
            state.liveFlow = null;
            state.relatedMessages = [];
            state.pendingAutoTaskTracker = null;
            renderMessages();
                renderThread();
                renderDetails();
                renderMessageComposerContext();
            }
            if (window.innerWidth <= 1140) {
                setSidebarOpen(false);
            }
        });
    });
}

function renderMessages() {
    renderMessageFilters();
    if (!state.selectedSessionId) {
        dom.messagePanelHint.textContent = "先创建或选中一个 session，再记录消息。";
        dom.messageSummary.innerHTML = "";
        dom.messageList.innerHTML = emptyState("当前没有 session message。记录第一条消息时会自动创建会话。");
        return;
    }

    const filteredMessages = filteredSessionMessages();
    const facadeReplyHighlight = buildFacadeReplyHighlightPlan(
        filteredMessages,
        scopedLastFacadeReply(selectedTask()),
        state.selectedTaskId || ""
    );
    dom.messageSummary.innerHTML = renderMessageSummary(filteredMessages, selectedTask(), state.liveFlow);
    dom.messagePanelHint.textContent = state.messages.length > 0
        ? `当前 thread 共 ${state.messages.length} 条消息，当前筛出 ${filteredMessages.length} 条${describeMessageFilterSummary()}。`
        : "当前 thread 还没有消息，可以先继续对话。";
    dom.messageList.innerHTML = filteredMessages.length > 0
        ? filteredMessages.map((message) => renderMessageCard(message, { facadeReplyHighlight })).join("")
        : emptyState(emptyMessageFilterText());
}

function renderThread() {
    renderThreadDrawer();
    if (state.tasks.length === 0) {
        dom.taskThread.innerHTML = emptyState("当前 thread 还没有 task。需要时可在下方输入后再展开控制项。");
        return;
    }

    const filteredTasks = state.taskStatusFilter === "all"
        ? state.tasks
        : state.tasks.filter(task => (task.status || "active").toLowerCase() === state.taskStatusFilter);

    if (filteredTasks.length === 0) {
        dom.taskThread.innerHTML = emptyState(`没有 ${state.taskStatusFilter} 状态的任务。`);
        return;
    }

    const tasksById = mapById(filteredTasks);
    const chains = buildTaskChains(filteredTasks);
    dom.taskThread.innerHTML = chains.map((chain, chainIndex) => {
        const selectedInChain = chain.tasks.some((task) => task.id === state.selectedTaskId) ? "is-active" : "";
        const rootTask = chain.rootTask || chain.tasks[0];
        const latestTask = chain.latestTask || chain.tasks[chain.tasks.length - 1];
        return `
            <section class="dialogue-chain ${selectedInChain}">
                <header class="dialogue-chain__header">
                    <div>
                        <p class="eyebrow">Task Thread ${String(chainIndex + 1).padStart(2, "0")}</p>
                        <h3 class="dialogue-chain__title">${escapeHtml(rootTask?.title || chain.rootId)}</h3>
                    </div>
                    <div class="dialogue-chain__meta">
                        <span class="task-badge">${escapeHtml(`${chain.tasks.length} rounds`)}</span>
                        <span class="task-badge" data-tone="${toneForStatus(latestTask?.status)}">${escapeHtml(latestTask?.status || "active")}</span>
                    </div>
                </header>
                <div class="dialogue-chain__messages">
                    ${chain.tasks.map((task, taskIndex) => renderThreadTask(task, taskIndex, tasksById)).join("")}
                </div>
            </section>
        `;
    }).join("");
}

function renderThreadTask(task, taskIndex, tasksById) {
    const active = task.id === state.selectedTaskId ? "is-active" : "";
    const flow = task.id === state.selectedTaskId ? state.liveFlow : null;
    const parentTask = taskParent(task, tasksById);
    const assistantSignals = buildAssistantSignals(task, flow);
    const workerLabel = activeWorkerLabel(task, flow);
    const executionStrip = buildThreadExecutionStrip(task, flow, workerLabel);
    const outcomeStrip = buildThreadOutcomeStrip(task, flow, 240);
    const outputPreview = assistantOutputPreview(task, flow, 260);
    const outputFullContent = assistantOutputFullContent(task, flow);
    const outputExpanded = Boolean(outputFullContent) && isThreadOutputExpanded(task.id);
    const outputExpandable = Boolean(outputFullContent) && outputFullContent !== outputPreview;
    const roundLabel = taskIndex === 0 ? "root" : `round ${taskIndex + 1}`;
    return `
        <article class="dialogue-task ${active}" data-task-id="${escapeHtml(task.id)}" role="button" tabindex="0" aria-label="查看任务 ${escapeHtml(task.title || task.id)}">
            <div class="dialogue-task__rail">
                <span class="dialogue-task__round">${escapeHtml(roundLabel)}</span>
                <span class="dialogue-task__time">${formatTime(task.created_at || task.createdAt)}</span>
                <span class="dialogue-task__line"></span>
            </div>
            <div class="dialogue-task__stack">
                <div class="dialogue-task__bubble dialogue-task__bubble--user">
                    <div class="dialogue-task__meta">
                        <span class="dialogue-task__role">Brief</span>
                        <span>${escapeHtml(task.title || task.id)}</span>
                        ${task.goal ? `<span>${escapeHtml(preview(task.goal, 84))}</span>` : ""}
                        ${parentTask ? `<span>follow-up of ${escapeHtml(parentTask.title || parentTask.id)}</span>` : ""}
                    </div>
                    <div class="dialogue-task__body">${escapeHtml(buildUserMessage(task))}</div>
                </div>
                <div class="dialogue-task__bubble dialogue-task__bubble--assistant">
                    ${executionStrip ? `
                        <div class="dialogue-task__runline">
                            <span class="dialogue-task__runlabel">${escapeHtml(executionStrip.label)}</span>
                            <div class="dialogue-task__runcontent">
                                ${executionStrip.title ? `<strong class="dialogue-task__runheadline">${escapeHtml(executionStrip.title)}</strong>` : ""}
                                ${executionStrip.detail ? `<span class="dialogue-task__rundetail">${escapeHtml(executionStrip.detail)}</span>` : ""}
                            </div>
                        </div>
                    ` : ""}
                    ${outcomeStrip ? `
                        <div class="dialogue-task__outcome">
                            <span class="dialogue-task__outlabel">${escapeHtml(outcomeStrip.label)}</span>
                            <div class="dialogue-task__outcontent">
                                ${outcomeStrip.title ? `<strong class="dialogue-task__outheadline">${escapeHtml(outcomeStrip.title)}</strong>` : ""}
                                ${outcomeStrip.detail ? `<span class="dialogue-task__outdetail">${escapeHtml(outcomeStrip.detail)}</span>` : ""}
                            </div>
                        </div>
                    ` : ""}
                    <div class="dialogue-task__meta">
                        <span class="dialogue-task__role">Harness</span>
                        <span class="task-badge" data-tone="${toneForStatus(task.status)}">${escapeHtml(task.status || "active")}</span>
                        <span class="task-badge">${escapeHtml(task.control_node || task.controlNode || "intake")}</span>
                        ${renderStartModeBadge(task)}
                        ${workerLabel ? `<span class="task-badge" data-tone="active">${escapeHtml(`worker · ${workerLabel}`)}</span>` : ""}
                    </div>
                    <div class="dialogue-task__body">${escapeHtml(buildAssistantMessage(task, flow))}</div>
                    ${outputPreview ? `
                        <div class="dialogue-task__output ${outputExpanded ? "dialogue-task__output--expanded" : ""}">
                            <div class="dialogue-task__output-copy">${escapeHtml(outputExpanded ? outputFullContent : outputPreview)}</div>
                            ${outputExpandable ? `
                                <button class="dialogue-task__output-toggle" type="button" data-thread-output-toggle="${escapeHtml(task.id)}">
                                    ${escapeHtml(outputExpanded ? "收起完整结果" : "展开完整结果")}
                                </button>
                            ` : ""}
                        </div>
                    ` : ""}
                    ${assistantSignals.length > 0 ? `
                        <div class="dialogue-task__signals">
                            ${assistantSignals.map((signal) => `<span class="signal">${escapeHtml(signal)}</span>`).join("")}
                        </div>
                    ` : ""}
                    <div class="dialogue-task__foot">
                        <span>${escapeHtml(task.id)}</span>
                    </div>
                </div>
            </div>
        </article>
    `;
}

function renderDetails() {
    const flow = state.liveFlow;
    const task = flow?.task || selectedTask();
    if (!task && state.selectedTaskId && state.selectedTaskLoading) {
        dom.detailTitle.textContent = "任务加载中";
        dom.selectedStatus.textContent = "loading";
        dom.taskDetailsEmpty.hidden = false;
        dom.taskOverview.hidden = true;
        dom.taskActions.hidden = true;
        dom.taskActionDrawer.hidden = true;
        dom.taskDetailsScroll.hidden = true;
        dom.taskOverview.innerHTML = "";
        dom.taskActions.innerHTML = "";
        dom.taskSecondaryActions.innerHTML = "";
        dom.taskActionDrawer.open = false;
        dom.chainContext.innerHTML = emptyState("任务 live flow 加载中。");
        dom.relatedMessages.innerHTML = emptyState("正在加载任务关联消息。");
        dom.continuitySummary.innerHTML = "正在加载任务 continuity 摘要。";
        dom.continuityChips.innerHTML = "";
        dom.mountedContext.innerHTML = emptyState("正在加载 mounted context。");
        dom.routeBox.innerHTML = emptyState("正在加载 route preview。");
        dom.experimentSummary.innerHTML = emptyState("正在加载 experiment summary。");
        dom.decisionList.innerHTML = emptyState("正在加载 decision。");
        dom.artifactList.innerHTML = emptyState("正在加载 artifact。");
        dom.agentActionList.innerHTML = emptyState("正在加载 reconciled action。");
        dom.toolList.innerHTML = emptyState("正在加载 tool trace。");
        setTaskActionState(false);
        renderDetailsPanelState();
        return;
    }
    if (!task) {
        dom.detailTitle.textContent = "选择一个任务";
        dom.selectedStatus.textContent = "idle";
        dom.taskDetailsEmpty.hidden = false;
        dom.taskOverview.hidden = true;
        dom.taskActions.hidden = true;
        dom.taskActionDrawer.hidden = true;
        dom.taskDetailsScroll.hidden = true;
        dom.taskOverview.innerHTML = "";
        dom.taskActions.innerHTML = "";
        dom.taskSecondaryActions.innerHTML = "";
        dom.taskActionDrawer.open = false;
        dom.chainContext.innerHTML = emptyState("当前没有迭代链。");
        dom.relatedMessages.innerHTML = emptyState("选中一个任务后，这里会显示它关联的 session messages。");
        dom.continuitySummary.innerHTML = "选中一个任务后，这里会显示 active context 和 continuity 摘要。";
        dom.continuityChips.innerHTML = "";
        dom.mountedContext.innerHTML = emptyState("当前任务没有 mounted context。");
        dom.routeBox.innerHTML = emptyState("暂无 route preview");
        dom.experimentSummary.innerHTML = emptyState("当前任务不属于 experiment batch。");
        dom.decisionList.innerHTML = emptyState("暂无 decision");
        dom.artifactList.innerHTML = emptyState("暂无 artifact");
        dom.agentActionList.innerHTML = emptyState("暂无 reconciled action");
        dom.toolList.innerHTML = emptyState("暂无 tool trace");
        setTaskActionState(false);
        renderDetailsPanelState();
        return;
    }

    const activeContext = flow?.runtime_context?.active_context || flow?.runtimeContext?.activeContext || {};
    const continuitySummary = firstNonBlank(
        activeContext.continuity_summary,
        activeContext.continuitySummary,
        task.summary,
        latestOutput(flow)
    );
    const openQuestions = activeContext.open_questions || activeContext.openQuestions || [];
    const nextCandidates = activeContext.next_candidates || activeContext.nextCandidates || [];
    const judgmentTrace = flow?.judgment_trace || flow?.judgmentTrace || {};
    const decisions = flow?.decisions || [];
    const recentArtifacts = (flow?.runtime_context?.recent_artifacts || flow?.runtimeContext?.recentArtifacts || []).slice(0, 5);
    const tools = (flow?.tool_invocations || flow?.toolInvocations || []).slice(0, 6);
    const experimentRun = experimentRunView(flow);
    const experimentMode = firstNonBlank(
        experimentRun.model_mode,
        experimentRun.modelMode,
        task.metadata?.model_mode,
        task.metadata?.modelMode,
        "ad hoc"
    );
    const toolFacts = toolChainFacts(flow, tools);
    const toolLabel = toolChainLabel(flow, tools);
    const toolSummary = toolChainNarrative(flow, tools);
    const workerLabel = activeWorkerLabel(task, flow);
    const focusLineBase = buildTaskFocusLineBase(task, flow);
    const overviewPlan = buildTaskOverviewPlan(task, {
        workerLabel: workerLabel ? `当前执行/最近执行: ${workerLabel}` : (task.assigned_worker || task.assignedWorker || "unassigned"),
        focusWorker: workerLabel,
        focusLineBase,
        experimentMode: humanizeToken(experimentMode) || experimentMode,
        toolLabel: toolLabel || "none"
    });
    const relatedMessagesPlan = buildRelatedMessagesPlan(state.relatedMessages);
    const continuityPlan = buildContinuitySummaryPlan({
        summary: continuitySummary,
        openQuestions,
        nextCandidates
    });
    const taskActionPlan = buildTaskActionPlan(task);
    const recoveryJobPlan = buildRecoveryJobPlan(state.recoveryJobs, { formatTime });
    const taskActionRender = renderTaskActionHtml(taskActionPlan, {
        renderButton: renderTaskActionButton,
        renderEmpty: emptyState
    });
    const headerRender = renderTaskHeaderHtml({
        focusLine: overviewPlan.focusLine,
        overviewCards: overviewPlan.cards,
        primaryAction: taskActionPlan.primary,
        secondaryActions: taskActionPlan.secondary.filter((item) => item.action !== "handoff")
    }, {
        renderCard: (item) => overviewCard(item.label, item.value),
        renderAction: (item) => item ? renderTaskActionButton(item, item.action !== taskActionPlan.primary?.action) : emptyState("当前任务已到终态；如需继续处理，可使用移交或新建 follow-up。")
    });

    dom.detailTitle.textContent = task.title || task.id;
    dom.selectedStatus.textContent = headerRender.focusLine;
    dom.taskDetailsEmpty.hidden = true;
    dom.taskOverview.hidden = false;
    dom.taskActions.hidden = false;
    dom.taskDetailsScroll.hidden = false;
    dom.taskOverview.innerHTML = headerRender.overviewHtml + renderRecoveryJobPanel(recoveryJobPlan);
    dom.taskActions.innerHTML = taskActionRender.primaryHtml;
    dom.taskSecondaryActions.innerHTML = taskActionRender.secondaryHtml;
    dom.taskActionDrawer.hidden = headerRender.secondaryHidden;
    if (taskActionPlan.primary == null) {
        dom.taskActionDrawer.open = false;
    }

    dom.chainContext.innerHTML = renderChainContext(task);
    dom.relatedMessages.innerHTML = state.relatedMessages.length > 0
        ? `
            <div class="stack-list">
                ${relatedMessagesPlan.visibleMessages
                    .map((message) => renderMessageCard(message, { compact: true, relatedOnly: true }))
                    .join("")}
            </div>
            ${relatedMessagesPlan.hasDrawer ? `
                <details class="inline-preview-drawer">
                    <summary class="inline-preview-drawer__summary">${escapeHtml(relatedMessagesPlan.drawerSummary)}</summary>
                    <div class="inline-preview-drawer__body stack-list">
                        ${relatedMessagesPlan.hiddenMessages
                            .map((message) => renderMessageCard(message, { compact: true, relatedOnly: true }))
                            .join("")}
                    </div>
                </details>
            ` : ""}
        `
        : emptyState("当前任务还没有关联消息。");
    dom.continuitySummary.innerHTML = `
        <div class="rich-copy">${escapeHtml(continuityPlan.previewText)}</div>
        ${continuityPlan.hasDrawer ? `
            <details class="inline-preview-drawer">
                <summary class="inline-preview-drawer__summary">${escapeHtml(continuityPlan.drawerSummary)}</summary>
                <div class="inline-preview-drawer__body">
                    <div class="rich-copy">${escapeHtml(continuitySummary || "暂无 continuity summary")}</div>
                    ${continuityPlan.hiddenChips.length > 0 ? `
                        <div class="chip-list">
                            ${continuityPlan.hiddenChips.map((line) => `<span class="chip">${escapeHtml(line)}</span>`).join("")}
                        </div>
                    ` : ""}
                </div>
            </details>
        ` : ""}
    `;
    dom.continuityChips.innerHTML = continuityPlan.visibleChips
        .map((line) => `<span class="chip">${escapeHtml(line)}</span>`)
        .join("");
    dom.mountedContext.innerHTML = renderMountedContext((flow?.runtime_context || flow?.runtimeContext || {}).mounted_context_view || (flow?.runtime_context || flow?.runtimeContext || {}).mountedContextView);

    dom.routeBox.innerHTML = renderRouteBox(flow, task);
    dom.experimentSummary.innerHTML = renderExperimentSummary(flow, state.experimentSummary);

    const decisionCards = [];
    const executionJudgment = judgmentTrace.execution_judgment || judgmentTrace.executionJudgment;
    const completionJudgment = judgmentTrace.completion_judgment || judgmentTrace.completionJudgment;
    const judgmentExecutionBoundary = judgmentTrace.execution_boundary || judgmentTrace.executionBoundary;
    const judgmentRuntimeFacts = judgmentTrace.runtime_facts || judgmentTrace.runtimeFacts || {};
    if (executionJudgment) {
        decisionCards.push(decisionCard("execution", executionJudgment, judgmentExecutionBoundary, judgmentRuntimeFacts));
    }
    if (completionJudgment) {
        decisionCards.push(decisionCard("completion", completionJudgment, judgmentExecutionBoundary, judgmentRuntimeFacts));
    }
    decisions.slice(0, 4).forEach((decision) => {
        decisionCards.push(decisionCard(decision.decision_type || decision.decisionType || "decision", decision));
    });
    dom.decisionList.innerHTML = decisionCards.length > 0 ? decisionCards.join("") : emptyState("暂无 decision");

    dom.artifactList.innerHTML = recentArtifacts.length > 0
        ? recentArtifacts.map(renderArtifactCard).join("")
        : emptyState("暂无 artifact");

    dom.agentActionList.innerHTML = renderAgentActions(state.agentActions);

    const toolCards = tools.map((tool) => stackItem(
            tool.tool_name || tool.toolName || "tool",
            toolTraceStatusLabel(tool),
            toolTraceSummary(tool),
            toolTraceMeta(tool)
        ));
    if (toolSummary) {
        toolCards.unshift(renderToolChainSummaryCard(toolFacts, toolLabel, toolSummary));
    }
    dom.toolList.innerHTML = toolCards.length > 0 ? toolCards.join("") : emptyState("当前任务还没有 tool trace");

    setTaskActionState(true);
    renderComposerContext();
    renderDetailsPanelState();
}

function renderWorkerOptions() {
    const options = state.workers.map((worker) => {
        const workerId = worker.worker_id || worker.workerId;
        return `<option value="${escapeHtml(workerId)}">${escapeHtml(workerId)}</option>`;
    });
    dom.handoffWorker.innerHTML = options.join("");
    dom.handoffButton.disabled = options.length === 0;
    dom.taskAssignedWorker.innerHTML = `<option value="">自动选择</option>${options.join("")}`;
}

function renderTaskActionButton(item, ghost) {
    return `<button class="button ${ghost ? "button--ghost" : ""}" data-task-action="${escapeHtml(item.action)}" type="button">${escapeHtml(item.label)}</button>`;
}

function renderMessageCard(message, options = {}) {
    const role = normalizeMessageRole(message.role);
    const type = normalizeMessageType(message.message_type || message.messageType);
    const messageId = firstNonBlank(message?.id);
    const taskId = messageTaskId(message);
    const isRelated = taskId && taskId === state.selectedTaskId;
    const compact = options.compact === true;
    const messageView = buildMessageDisplayView(message);
    const metadata = messageView?.metadata && typeof messageView.metadata === "object" ? messageView.metadata : {};
    const failureClass = messageCardFailureClass(metadata);
    const signalPlan = buildMessageSignalPlan(metadata, options);
    const body = messageCardBody(messageView, compact);
    const detail = messageCardDetail(messageView, compact);
    const outcomeStrip = messageCardOutcomeStrip(messageView, compact);
    const executionStrip = messageCardExecutionStrip(messageView, compact);
    const continuityScope = firstNonBlank(
        metadata.continuity_scope,
        metadata.continuityScope
    );
    const isLatestFacadeReply = options.facadeReplyHighlight?.messageId === message.id;
    
    const isProcessMessage = isProcessType(type);
    const expansionPlan = buildMessageExpansionPlan(messageView, body, { maxCollapsedLength: compact ? 180 : 300 });
    const needsExpand = expansionPlan.needsExpand;
    const isExpanded = needsExpand && isMessageExpanded(messageId);
    const previewBody = body;
    
    const actions = [];
    if (canUseMessageAsDraft(message)) {
        actions.push(
            `<button class="link-button" type="button" data-message-action="use-draft" data-message-id="${escapeHtml(message.id)}">用作任务草稿</button>`
        );
    }
    if (taskId) {
        actions.push(
            `<button class="link-button" type="button" data-message-action="view-task" data-message-id="${escapeHtml(message.id)}">查看关联任务</button>`
        );
    }
    const classes = [
        "message-card",
        compact ? "message-card--compact" : "",
        isRelated ? "is-related" : "",
        isLatestFacadeReply ? "is-facade-reply" : "",
        needsExpand ? "message-card--expandable" : "",
        isExpanded ? "message-card--expanded" : "",
        isProcessMessage ? "message-card--process" : "",
        `message-card--${role}`
    ].filter(Boolean).join(" ");

    return `
        <article class="${classes}" data-message-id="${escapeHtml(message.id)}">
            <div class="message-card__meta">
                <span class="task-badge" data-tone="${messageRoleTone(role)}">${escapeHtml(formatMessageRole(role))}</span>
                <span class="task-badge">${escapeHtml(formatMessageType(type))}</span>
                ${continuityScope === "session" ? `<span class="task-badge" data-tone="manual">session continuity</span>` : ""}
                ${continuityScope === "task" ? `<span class="task-badge" data-tone="active">task-bound</span>` : ""}
                ${failureClass ? `<span class="task-badge" data-tone="warn">${escapeHtml(`failure · ${preview(failureClass, 22)}`)}</span>` : ""}
                ${isLatestFacadeReply ? renderFacadeReplyBadgeHtml(options.facadeReplyHighlight, { escapeHtml }) : ""}
                ${renderMessageSignals(signalPlan)}
                ${taskId ? `<span class="task-badge" data-tone="${isRelated ? "active" : "default"}">${escapeHtml(`task · ${preview(taskId, 18)}`)}</span>` : ""}
                <span>${formatTime(message.created_at || message.createdAt)}</span>
            </div>
            ${executionStrip ? `
                <div class="message-card__execution-strip">
                    <span class="message-card__execution-label">${escapeHtml(executionStrip.label)}</span>
                    <div class="message-card__execution-content">
                        ${executionStrip.title ? `<strong class="message-card__execution-headline">${escapeHtml(executionStrip.title)}</strong>` : ""}
                        ${executionStrip.detail ? `<span class="message-card__execution-detail">${escapeHtml(executionStrip.detail)}</span>` : ""}
                    </div>
                </div>
            ` : ""}
            ${outcomeStrip ? `
                <div class="message-card__outcome-strip">
                    <span class="message-card__outcome-label">${escapeHtml(outcomeStrip.label)}</span>
                    <div class="message-card__outcome-content">
                        ${outcomeStrip.title ? `<strong class="message-card__outcome-headline">${escapeHtml(outcomeStrip.title)}</strong>` : ""}
                        ${outcomeStrip.detail ? `<span class="message-card__outcome-detail">${escapeHtml(outcomeStrip.detail)}</span>` : ""}
                    </div>
                </div>
            ` : ""}
            ${needsExpand ? `
                <div class="message-card__body message-card__collapsed-body">${escapeHtml(previewBody)}</div>
                <div class="message-card__body message-card__full-content">${escapeHtml(expansionPlan.fullContent)}</div>
            ` : `
                <div class="message-card__body">${escapeHtml(body)}</div>
            `}
            ${detail ? `<div class="message-card__hint">${escapeHtml(detail)}</div>` : ""}
            ${options.relatedOnly && taskId ? `<div class="message-card__hint mono">${escapeHtml(taskId)}</div>` : ""}
            ${needsExpand ? `
                <div class="message-card__expand-indicator" data-message-action="toggle-expand">
                    <span class="message-card__expand-chevron">&gt;</span>
                    <span class="message-card__expand-label">${escapeHtml(messageExpansionToggleLabel(message, isExpanded))}</span>
                </div>
            ` : ""}
            ${actions.length > 0 ? `<div class="message-card__actions">${actions.join("")}</div>` : ""}
        </article>
    `;
}

function isProcessType(type) {
    const processTypes = [
        "thinking",
        "reasoning",
        "tool_call",
        "tool_result",
        "action",
        "execution",
        "system",
        "internal"
    ];
    return processTypes.includes((type || "").toLowerCase());
}

function renderMessageComposerContext() {
    const session = currentSession();
    const task = selectedTask();
    const sessionClosed = isClosedSession(session);
    const plan = composerSubmissionPlan();
    const messageMode = plan.resolvedMode === "message";
    const referencedTask = resolveComposerReferencedTask(plan);

    if (dom.messageAttachTask) {
        dom.messageAttachTask.disabled = true;
        dom.messageAttachTask.checked = false;
    }
    syncComposerSecondaryActions(task, followupSourceTask(), sessionClosed, messageMode);

    if (dom.messageHint) {
        const sessionLine = sessionClosed
            ? `${session?.title || session?.id || "当前 thread"} · 已关闭`
            : session
                ? `${session.title || session.id}`
                : "自动创建新 thread";
        const facadeLine = facadeSurfaceSummaryLabel(state.facadeSurface);
        const routingLine = plan.resolvedMode === "followup"
            ? `follow-up -> ${followupSourceTask()?.title || followupSourceTask()?.id || "parent"}`
            : referencedTask
                ? `继续 ${referencedTask.title || referencedTask.id}`
                : plan.resolvedMode === "task"
                    ? "显式新任务"
                    : "发送后 materialize 成新 task";
        const autoStartLine = !dom.taskAutoStart.checked ? "manual-start" : null;
        const closedLine = sessionClosed ? "closed session 不再接受新输入" : null;
        dom.messageHint.textContent = [sessionLine, facadeLine, routingLine, autoStartLine, closedLine].filter(Boolean).join(" · ");
    }
    if (dom.composerRoutingMeta) {
        dom.composerRoutingMeta.textContent = plan.reasonLabel || "默认聊天推进";
    }
    if (dom.composerModeHint) {
        dom.composerModeHint.textContent = sessionClosed
            ? "当前 session 已关闭，不能继续聊天或发布任务。"
            : plan.resolvedMode === "followup"
                ? `当前会直接发布 follow-up task${followupSourceTask() ? `：${followupSourceTask().title || followupSourceTask().id}` : ""}。`
                : plan.resolvedMode === "task"
                    ? (dom.taskContinueCurrent.checked && task
                        ? `当前会作为 existing-task continuity 继续 ${task.title || task.id}。原因：${plan.reasonLabel}。`
                        : `当前会直接发布新任务。原因：${plan.reasonLabel}。`)
                    : referencedTask
                        ? `当前会作为 task continuity 继续 ${referencedTask.title || referencedTask.id}。原因：${plan.reasonLabel}。`
                        : "默认聊天会直接进入 harness；若当前没有 task，这一轮会自动新建。";
    }
}

function syncComposerSecondaryActions(task, followupParent, sessionClosed, messageMode = null) {
    const effectiveMessageMode = messageMode ?? composerSubmissionPlan().resolvedMode === "message";
    const showAttach = false;
    const showFollowup = Boolean(task) && !sessionClosed;
    const showClearFollowup = Boolean(followupParent) && !sessionClosed;
    const showContextBlock = sessionClosed || Boolean(task) || Boolean(followupParent);

    if (dom.messageAttachTaskWrap) {
        dom.messageAttachTaskWrap.hidden = !showAttach;
    }
    if (dom.followupButton) {
        dom.followupButton.hidden = !showFollowup;
    }
    if (dom.clearFollowupButton) {
        dom.clearFollowupButton.hidden = !showClearFollowup;
    }
    if (dom.composerContextBlock) {
        dom.composerContextBlock.hidden = !showContextBlock;
    }
}

function renderMessageSummary(messages, task = null, flow = null) {
    const pinnedOutput = renderPinnedTaskOutcomeSummary(task, flow);
    const summaries = ["assistant", "system"]
        .map((role) => buildMessageRoleSummary(messages, role))
        .filter(Boolean);
    const stackPlan = buildMessageSummaryStackPlan(summaries);
    const roleSummary = stackPlan.primary
        ? renderMessageSummaryStackHtml(stackPlan, {
            renderCard: renderMessageSummaryCard,
            renderBrief: renderMessageSummaryBrief
        })
        : "";
    if (!pinnedOutput && !roleSummary) {
        return "";
    }
    return [pinnedOutput, roleSummary].filter(Boolean).join("");
}

function renderMessageSummaryCard(summary) {
    return `
        <section class="message-summary-card" data-role="${escapeHtml(summary.role)}">
            <div class="message-summary-card__meta">
                <span class="task-badge" data-tone="${messageRoleTone(summary.role)}">${escapeHtml(formatMessageRole(summary.role))}</span>
                <span>${escapeHtml(`${summary.count} msgs`)}</span>
                ${summary.latestTaskId ? `<span>${escapeHtml(`task · ${preview(summary.latestTaskId, 14)}`)}</span>` : ""}
                ${summary.latestAt ? `<span>${escapeHtml(formatTime(summary.latestAt))}</span>` : ""}
            </div>
            <div class="message-summary-card__body">${escapeHtml(summary.latestText || "暂无可读摘要。")}</div>
            ${summary.primarySignal ? `
                <div class="message-summary-card__signals">
                    <span class="signal">${escapeHtml(summary.primarySignal)}</span>
                </div>
            ` : ""}
            ${summary.topTypeLine ? `
                <div class="message-summary-card__foot">
                    ${escapeHtml(summary.topTypeLine)}
                </div>
            ` : ""}
        </section>
    `;
}

function renderMessageSummaryBrief(summary) {
    return `
        <section class="message-summary-brief" data-role="${escapeHtml(summary.role)}">
            <div class="message-summary-brief__meta">
                <span class="task-badge" data-tone="${messageRoleTone(summary.role)}">${escapeHtml(formatMessageRole(summary.role))}</span>
                <span>${escapeHtml(`${summary.count} msgs`)}</span>
                ${summary.latestAt ? `<span>${escapeHtml(formatTime(summary.latestAt))}</span>` : ""}
            </div>
            <div class="message-summary-brief__body">
                ${escapeHtml(summary.primarySignal || summary.latestText || "暂无摘要。")}
            </div>
        </section>
    `;
}

function renderPinnedTaskOutcomeSummary(task, flow) {
    const taskId = firstNonBlank(task?.id);
    const flowTaskId = firstNonBlank(flow?.task?.id);
    if (!taskId) {
        return "";
    }
    if (flowTaskId && flowTaskId !== taskId) {
        return "";
    }
    const workerLabel = activeWorkerLabel(task, flow);
    const executionStrip = buildThreadExecutionStrip(task, flow, workerLabel);
    const outcomeStrip = buildThreadOutcomeStrip(task, flow, 260);
    const outputPreview = pinnedTaskOutcomePreview(task, flow, 240);
    if (!executionStrip && !outcomeStrip && !outputPreview) {
        return "";
    }
    const taskMetadata = (flowTaskId === taskId ? flow?.task?.metadata : null) || task?.metadata || {};
    const detail = messageCardRecoveryDetail(taskMetadata, true);
    const failureClass = humanizeFailureClass(firstNonBlank(taskMetadata.failure_class, taskMetadata.failureClass));
    const showBody = Boolean(outputPreview) && !outcomeStrip;
    return `
        <section class="message-summary-card message-summary-card--pinned" data-role="active-task" data-testid="pinned-latest-round-output">
            <div class="message-summary-card__meta">
                <span class="task-badge" data-tone="active">latest round output</span>
                <span>${escapeHtml(preview(task.title || task.id, 32))}</span>
                ${workerLabel ? `<span>${escapeHtml(`worker · ${workerLabel}`)}</span>` : ""}
                ${failureClass ? `<span>${escapeHtml(`failure · ${preview(failureClass, 28)}`)}</span>` : ""}
                <span>${escapeHtml(formatTime(task.updated_at || task.updatedAt || task.created_at || task.createdAt))}</span>
            </div>
            ${executionStrip ? `
                <div class="message-summary-card__execution-strip">
                    <span class="message-summary-card__execution-label">${escapeHtml(executionStrip.label)}</span>
                    <div class="message-summary-card__execution-content">
                        ${executionStrip.title ? `<strong class="message-summary-card__execution-headline">${escapeHtml(executionStrip.title)}</strong>` : ""}
                        ${executionStrip.detail ? `<span class="message-summary-card__execution-detail">${escapeHtml(executionStrip.detail)}</span>` : ""}
                    </div>
                </div>
            ` : ""}
            ${outcomeStrip ? `
                <div class="message-summary-card__outcome-strip">
                    <span class="message-summary-card__outcome-label">${escapeHtml(outcomeStrip.label)}</span>
                    <div class="message-summary-card__outcome-content">
                        ${outcomeStrip.title ? `<strong class="message-summary-card__outcome-headline">${escapeHtml(outcomeStrip.title)}</strong>` : ""}
                        ${outcomeStrip.detail ? `<span class="message-summary-card__outcome-detail">${escapeHtml(outcomeStrip.detail)}</span>` : ""}
                    </div>
                </div>
            ` : ""}
            ${showBody ? `<div class="message-summary-card__body">${escapeHtml(outputPreview)}</div>` : ""}
            ${detail ? `<div class="message-summary-card__foot">${escapeHtml(detail)}</div>` : ""}
        </section>
    `;
}

function pinnedTaskOutcomePreview(task, flow, max = 240) {
    const latestOutcome = latestTaskOutcomeMessage(task, flow);
    const outcomeMetadata = latestOutcome?.metadata || {};
    const taskMetadata = (flow?.task?.metadata && firstNonBlank(flow?.task?.id) === firstNonBlank(task?.id))
        ? flow.task.metadata
        : (task?.metadata || {});
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

function looksLikeTerseOutcomeToken(value) {
    const text = firstNonBlank(value, "");
    if (!text) {
        return false;
    }
    return /^(failed|done|ok|success|succeeded|completed?)$/i.test(text);
}

function onMessageFilterClick(event) {
    const button = event.target.closest("[data-filter-group][data-filter-value]");
    if (!button) {
        return;
    }

    const group = button.dataset.filterGroup;
    const value = button.dataset.filterValue;
    if (group === "role" && state.messageFilterRole !== value) {
        state.messageFilterRole = value;
        renderMessages();
        return;
    }
    if (group === "scope" && state.messageFilterScope !== value) {
        state.messageFilterScope = value;
        renderMessages();
    }
}

function onTaskStatusFilterClick(event) {
    const button = event.target.closest("[data-filter-group][data-filter-value]");
    if (!button) {
        return;
    }

    const value = button.dataset.filterValue;
    if (state.taskStatusFilter !== value) {
        state.taskStatusFilter = value;
        dom.taskStatusFilters.querySelectorAll(".filter-chip").forEach((chip) => {
            chip.classList.toggle("is-active", chip.dataset.filterValue === value);
        });
        renderThread();
    }
}

function renderMessageFilters() {
    renderMessageFilterGroup(dom.messageRoleFilters, "role", state.messageFilterRole);
    renderMessageFilterGroup(dom.messageScopeFilters, "scope", state.messageFilterScope);
}

function renderMessageFilterGroup(container, group, activeValue) {
    if (!container) {
        return;
    }
    container.querySelectorAll(`[data-filter-group="${group}"]`).forEach((button) => {
        button.classList.toggle("is-active", button.dataset.filterValue === activeValue);
    });
}

function filteredSessionMessages() {
    return state.messages.filter((message) =>
        messageMatchesRoleFilter(message, state.messageFilterRole)
        && messageMatchesScopeFilter(message, state.messageFilterScope)
    );
}

function messageMatchesRoleFilter(message, roleFilter) {
    if (!roleFilter || roleFilter === "all") {
        return true;
    }
    return normalizeMessageRole(message?.role) === roleFilter;
}

function messageMatchesScopeFilter(message, scopeFilter) {
    if (!scopeFilter || scopeFilter === "all") {
        return true;
    }
    const hasTask = Boolean(messageTaskId(message));
    if (scopeFilter === "task-only") {
        return hasTask;
    }
    if (scopeFilter === "session-only") {
        return !hasTask;
    }
    return true;
}

function describeMessageFilterSummary() {
    const roleLabel = state.messageFilterRole === "all" ? null : state.messageFilterRole;
    const scopeLabel = state.messageFilterScope === "all" ? null : state.messageFilterScope;
    const labels = [roleLabel, scopeLabel].filter(Boolean);
    return labels.length > 0 ? `（过滤：${labels.join(" / ")}）` : "";
}

function emptyMessageFilterText() {
    if (state.messages.length === 0) {
        return "当前 session 还没有消息。";
    }
    return `当前没有命中 ${describeMessageFilterSummary().replace(/[（）]/g, "") || "过滤条件"} 的消息。`;
}

function messageById(messageId) {
    return [...state.relatedMessages, ...state.messages].find((item) => item.id === messageId) || null;
}

function pruneExpandedMessageIds() {
    const visibleIds = new Set(
        [...state.relatedMessages, ...state.messages]
            .map((message) => firstNonBlank(message?.id))
            .filter(Boolean)
    );
    state.expandedMessageIds = new Set(
        [...(state.expandedMessageIds || [])].filter((messageId) => visibleIds.has(messageId))
    );
    const visibleTaskIds = new Set(
        state.tasks
            .map((task) => firstNonBlank(task?.id))
            .filter(Boolean)
    );
    state.expandedThreadOutputTaskIds = new Set(
        [...(state.expandedThreadOutputTaskIds || [])].filter((taskId) => visibleTaskIds.has(taskId))
    );
}

function isMessageExpanded(messageId) {
    return Boolean(messageId && state.expandedMessageIds?.has(messageId));
}

function setMessageExpanded(messageId, expanded) {
    if (!messageId) {
        return;
    }
    const next = new Set(state.expandedMessageIds || []);
    if (expanded) {
        next.add(messageId);
    } else {
        next.delete(messageId);
    }
    state.expandedMessageIds = next;
}

function isThreadOutputExpanded(taskId) {
    return Boolean(taskId && state.expandedThreadOutputTaskIds?.has(taskId));
}

function setThreadOutputExpanded(taskId, expanded) {
    if (!taskId) {
        return;
    }
    const next = new Set(state.expandedThreadOutputTaskIds || []);
    if (expanded) {
        next.add(taskId);
    } else {
        next.delete(taskId);
    }
    state.expandedThreadOutputTaskIds = next;
}

function messageExpansionToggleLabel(message, expanded) {
    const fullResult = hasExpandedTaskOutcomeContent(message);
    if (expanded) {
        return fullResult ? "收起完整结果" : "收起详细内容";
    }
    return fullResult ? "展开完整结果" : "展开详细内容";
}

function applyMessageDraft(message) {
    const content = firstNonBlank(message.content, "");
    if (!content) {
        showToast("消息内容为空，无法转成任务草稿", true);
        return;
    }

    const taskId = messageTaskId(message);
    if (taskId && state.tasks.some((task) => task.id === taskId)) {
        state.followupParentTaskId = taskId;
    }

    dom.taskTitle.value = deriveTitle(content);
    dom.taskIntent.value = content;
    renderComposerContext();
    dom.taskIntent.focus();
    dom.taskIntent.setSelectionRange(dom.taskIntent.value.length, dom.taskIntent.value.length);
    showToast(taskId ? `已生成任务草稿，并绑定 ${taskId}` : "已生成任务草稿");
}

function formatMessageRole(role) {
    switch (normalizeMessageRole(role)) {
        case "assistant":
            return "assistant";
        case "system":
            return "system";
        default:
            return "user";
    }
}

function formatMessageType(type) {
    switch (normalizeMessageType(type)) {
        case "task_brief":
            return "task brief";
        case "task_followup":
            return "task follow-up";
        case "task_note":
            return "task note";
        case "task_progress":
            return "task progress";
        case "task_result":
            return "task result";
        case "worker_round":
            return "worker round";
        case "task_receipt":
            return "task receipt";
        case "task_action":
            return "task action";
        case "task_state":
            return "task state";
        case "user_note":
            return "user note";
        default:
            return firstNonBlank(type, "message");
    }
}

function normalizeMessageRole(role) {
    switch ((role || "").toLowerCase()) {
        case "assistant":
        case "system":
            return role.toLowerCase();
        default:
            return "user";
    }
}

function normalizeMessageType(type) {
    return (type || "").toLowerCase();
}

function isWorkerOutcomeMessageType(type) {
    const normalized = normalizeMessageType(type);
    return normalized === "task_progress" || normalized === "task_result" || normalized === "worker_round";
}

function messageRoleTone(role) {
    switch (normalizeMessageRole(role)) {
        case "assistant":
            return "auto";
        case "system":
            return "manual";
        default:
            return "active";
    }
}

function canUseMessageAsDraft(message) {
    return normalizeMessageRole(message?.role) === "user";
}

function renderMessageSignals(plan) {
    return (plan?.entries || [])
        .map((entry) => signalBadge(entry.value, entry.tone, entry.label))
        .filter(Boolean)
        .join("");
}

function messageCardBody(message, compact = false) {
    const type = normalizeMessageType(message?.message_type || message?.messageType);
    const metadata = effectiveMessageMetadata(message);
    const summaryPreview = firstNonBlank(
        metadata.summary_preview,
        metadata.summaryPreview
    );
    const actionLabel = firstNonBlank(
        metadata.action_label,
        metadata.actionLabel
    );
    const reason = firstNonBlank(metadata.reason);
    const currentState = firstNonBlank(
        metadata.current_state,
        metadata.currentState
    );
    const previousState = firstNonBlank(
        metadata.previous_state,
        metadata.previousState
    );
    const content = firstNonBlank(message?.content, "");
    const max = compact ? 180 : 280;
    const workerOutcomePreview = messageCardWorkerOutcomePreview(message, compact ? 160 : 220);
    const readableFailureSummary = readableWorkerFailureSummary(type, metadata, summaryPreview, content);

    if (readableFailureSummary) {
        return preview(readableFailureSummary, max);
    }
    if (isWorkerOutcomeMessageType(type) && workerOutcomePreview) {
        return preview(workerOutcomePreview, max);
    }
    if (isWorkerOutcomeMessageType(type) && summaryPreview) {
        return preview(summaryPreview, max);
    }
    if (type === "task_action" && actionLabel) {
        const parts = [actionLabel, reason ? `原因：${reason}` : null].filter(Boolean);
        return preview(parts.join(" · "), max);
    }
    if (type === "task_state" && currentState) {
        const stateLine = previousState ? `${previousState} -> ${currentState}` : currentState;
        const parts = [stateLine, reason ? `原因：${reason}` : null].filter(Boolean);
        return preview(parts.join(" · "), max);
    }
    return preview(content, max);
}

function readableWorkerFailureSummary(type, metadata, summaryPreview, content) {
    if (!isWorkerOutcomeMessageType(type)) {
        return "";
    }
    const candidate = firstNonBlank(summaryPreview, content, "");
    if (!candidate || !looksUnreadableWorkerOutput(candidate) || !isFailureMessageMetadata(metadata)) {
        return "";
    }
    return buildUnreadableWorkerFailureSummary(metadata);
}

function effectiveMessageMetadata(message) {
    const base = message?.metadata && typeof message.metadata === "object" ? { ...message.metadata } : {};
    const taskId = messageTaskId(message);
    const focusedTaskId = firstNonBlank(state.liveFlow?.task?.id, state.selectedTaskId);
    if (!taskId || taskId !== focusedTaskId) {
        return base;
    }
    const flowTask = state.liveFlow?.task;
    if (firstNonBlank(flowTask?.id) !== taskId) {
        return base;
    }
    const flowMetadata = flowTask?.metadata && typeof flowTask.metadata === "object" ? flowTask.metadata : {};
    const merged = { ...base };
    const fallbackKeys = [
        "failure_class",
        "failure_summary_readable",
        "recovery_policy",
        "recovery_stage",
        "auto_same_worker_retry_count",
        "auto_handoff_count",
        "auto_handoff_target",
        "previous_worker",
        "assigned_worker"
    ];
    fallbackKeys.forEach((key) => {
        if (!firstNonBlank(merged[key], merged[toCamelKey(key)])) {
            const flowValue = flowMetadata[key] ?? flowMetadata[toCamelKey(key)];
            if (flowValue !== undefined && flowValue !== null && String(flowValue).trim() !== "") {
                merged[key] = flowValue;
            }
        }
    });
    return merged;
}

function buildMessageDisplayView(message) {
    const metadata = effectiveMessageMetadata(message);
    const baseView = metadata === message?.metadata ? message : { ...message, metadata };
    const projection = focusedTaskOutcomeProjection(baseView);
    if (!projection) {
        return baseView;
    }
    const projectedMetadata = { ...metadata };
    if (projection.preview) {
        projectedMetadata.summary_preview = projection.preview;
        projectedMetadata.summaryPreview = projection.preview;
    }
    const currentFullContent = firstNonBlank(projectedMetadata.full_content, projectedMetadata.fullContent);
    if (projection.fullContent && (!currentFullContent || isStaleTaskOutcomeShell(currentFullContent))) {
        projectedMetadata.full_content = projection.fullContent;
        projectedMetadata.fullContent = projection.fullContent;
    }
    if (projection.nextStep && !firstNonBlank(
        projectedMetadata.next_step,
        projectedMetadata.nextStep,
        projectedMetadata.suggested_next_step,
        projectedMetadata.suggestedNextStep
    )) {
        projectedMetadata.next_step = projection.nextStep;
        projectedMetadata.nextStep = projection.nextStep;
    }
    return {
        ...baseView,
        metadata: projectedMetadata
    };
}

function focusedTaskOutcomeProjection(message) {
    const taskId = messageTaskId(message);
    const flow = state.liveFlow;
    const flowTask = flow?.task;
    if (!taskId || !flowTask || firstNonBlank(flowTask?.id) !== taskId) {
        return null;
    }
    const type = normalizeMessageType(message?.message_type || message?.messageType);
    if (!isWorkerOutcomeMessageType(type)) {
        return null;
    }
    const latestOutcome = latestTaskOutcomeMessage(flowTask, flow);
    const outcomeMetadata = latestOutcome?.metadata || {};
    return {
        preview: assistantOutputPreview(flowTask, flow, 220),
        fullContent: assistantOutputFullContent(flowTask, flow),
        nextStep: taskOutcomeNextStep(flowTask, outcomeMetadata)
    };
}

function toCamelKey(snakeKey) {
    return String(snakeKey || "").replace(/_([a-z])/g, (_, char) => char.toUpperCase());
}

function looksUnreadableWorkerOutput(value) {
    const text = firstNonBlank(value, "");
    if (!text) {
        return false;
    }
    const replacementCount = (text.match(/�/g) || []).length;
    return replacementCount >= 2 || text.includes("����") || (text.includes("û") && text.includes("��"));
}

function isFailureMessageMetadata(metadata) {
    const status = firstNonBlank(
        metadata.execution_status,
        metadata.executionStatus,
        metadata.worker_execution_status,
        metadata.workerExecutionStatus,
        metadata.completion_status,
        metadata.completionStatus
    );
    if (status) {
        const normalized = status.toLowerCase();
        if (normalized === "failed" || normalized === "error" || normalized === "timeout"
            || normalized === "partial_timeout" || normalized === "cancelled") {
            return true;
        }
    }
    return Array.isArray(metadata.unfinished_items) && metadata.unfinished_items.length > 0;
}

function buildUnreadableWorkerFailureSummary(metadata) {
    const worker = firstNonBlank(
        metadata.selected_worker,
        metadata.selectedWorker,
        metadata.assigned_worker,
        metadata.assignedWorker,
        metadata.worker_id,
        metadata.workerId
    );
    return worker
        ? `worker ${worker} 返回了不可读错误输出；请打开 details / live_flow 查看失败轨迹。`
        : "当前 worker 返回了不可读错误输出；请打开 details / live_flow 查看失败轨迹。";
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

function messageCardWorkerOutcomePreview(message, max = 220) {
    const type = normalizeMessageType(message?.message_type || message?.messageType);
    if (!isWorkerOutcomeMessageType(type)) {
        return "";
    }
    const metadata = message?.metadata || {};
    const content = firstNonBlank(message?.content, "");
    const readableFailure = readableWorkerFailureSummary(
        type,
        metadata,
        firstNonBlank(metadata.summary_preview, metadata.summaryPreview),
        content
    );
    const fallbackFailure = sanitizeFailureSummaryForDisplay(
        firstNonBlank(metadata.failure_summary_readable, metadata.failureSummaryReadable),
        firstNonBlank(
            metadata.worker,
            metadata.worker_id,
            metadata.workerId,
            metadata.previous_worker,
            metadata.previousWorker,
            metadata.assigned_worker,
            metadata.assignedWorker,
            metadata.selected_worker,
            metadata.selectedWorker
        )
    );
    const candidate = firstNonBlank(
        readableFailure,
        fallbackFailure,
        metadata.summary_preview,
        metadata.summaryPreview,
        content
    );
    return candidate ? preview(candidate, max) : "";
}

function messageCardOutcomeStrip(message, compact = false) {
    const type = normalizeMessageType(message?.message_type || message?.messageType);
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
    const type = normalizeMessageType(message?.message_type || message?.messageType);
    if (!isWorkerOutcomeMessageType(type)) {
        return null;
    }
    const metadata = effectiveMessageMetadata(message);
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
    const sameWorkerRetryCount = numericValue(
        metadata.auto_same_worker_retry_count,
        metadata.autoSameWorkerRetryCount,
        metadata.same_worker_retry_count,
        metadata.sameWorkerRetryCount
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
        recoveryParts.push(`failure · ${failureClass}`);
    }
    if (recoveryStage) {
        recoveryParts.push(`recovery · ${recoveryStage}`);
    }
    const actionHint = recoveryActionHint(failureClass, recoveryStage);
    if (actionHint) {
        recoveryParts.push(`hint · ${actionHint}`);
    }
    if (sameWorkerRetryCount && sameWorkerRetryCount > 0) {
        recoveryParts.push(`retry ${sameWorkerRetryCount}`);
    }
    if (autoHandoffCount && autoHandoffCount > 0) {
        recoveryParts.push(
            handoffTarget
                ? `handoff ${autoHandoffCount} -> ${preview(handoffTarget, compact ? 12 : 18)}`
                : `handoff ${autoHandoffCount}`
        );
    }
    if (recoveryExecutionMode === "fresh_session") {
        recoveryParts.push("recovery · fresh session");
    }
    return recoveryParts.length > 0 ? recoveryParts.join("  ") : "";
}

function messageCardFailureClass(metadata) {
    return humanizeFailureClass(firstNonBlank(
        metadata.failure_class,
        metadata.failureClass
    ));
}

function messageCardDetail(message, compact = false) {
    const type = normalizeMessageType(message?.message_type || message?.messageType);
    const metadata = effectiveMessageMetadata(message);
    const nextStep = firstNonBlank(
        metadata.next_step,
        metadata.nextStep,
        metadata.suggested_next_step,
        metadata.suggestedNextStep
    );
    const current = firstNonBlank(
        metadata.task_status && metadata.control_node
            ? `${metadata.task_status} / ${metadata.control_node}`
            : null,
        metadata.taskStatus && metadata.controlNode
            ? `${metadata.taskStatus} / ${metadata.controlNode}`
            : null
    );
    const detailParts = [];
    if (isWorkerOutcomeMessageType(type)) {
        const recoveryDetail = messageCardRecoveryDetail(metadata, compact);
        if (recoveryDetail) {
            detailParts.push(recoveryDetail);
        }
    }
    if (isWorkerOutcomeMessageType(type) && nextStep) {
        detailParts.push(`next · ${preview(nextStep, compact ? 80 : 120)}`);
    }
    if ((type === "task_action" || type === "task_state" || type === "task_receipt") && current) {
        detailParts.push(`current · ${current}`);
    }
    return detailParts.length > 0 ? detailParts.join("  ") : "";
}

function signalBadge(value, tone, label) {
    const text = firstNonBlank(value);
    if (!text) {
        return "";
    }
    return `<span class="task-badge" data-tone="${escapeHtml(tone || "default")}">${escapeHtml(`${label} · ${preview(text, 24)}`)}</span>`;
}

function overviewCard(label, value) {
    return `
        <div class="overview-card">
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(value || "n/a")}</strong>
        </div>
    `;
}

function renderRecoveryJobPanel(plan) {
    if (!plan?.visible) {
        return "";
    }
    return `
        <div class="overview-card overview-card--wide">
            <span>Recovery Job</span>
            <strong>${escapeHtml(plan.summary)}</strong>
            <small class="message__hint mono">${escapeHtml(plan.requestId)}</small>
            ${plan.chips.length > 0 ? `
                <div class="dialogue-task__signals">
                    ${plan.chips.map((chip) => `<span class="signal">${escapeHtml(chip)}</span>`).join("")}
                </div>
            ` : ""}
            ${plan.error ? `<small class="message__hint">${escapeHtml(plan.error)}</small>` : ""}
        </div>
    `;
}

function decisionCard(type, decision, executionBoundary = null, runtimeFacts = null) {
    const diagnostics = judgmentDiagnosticFacts(decision, runtimeFacts, executionBoundary);
    const boundaryFacts = buildExecutionBoundaryFacts({ execution_boundary: executionBoundary }, []);
    return stackItem(
        type,
        decision.summary || "no summary",
        buildJudgmentCardBody({
            rationale: decision.rationale,
            reason: decision.reason,
            executionLine: boundaryFacts.traceSummary || boundaryFacts.label,
            metrics: diagnostics.metrics,
            cognitionRows: diagnostics.cognitionRows
        }),
        decision.created_at || decision.createdAt ? formatTime(decision.created_at || decision.createdAt) : ""
    );
}

function judgmentDiagnosticFacts(decision, runtimeFacts = null, executionBoundary = null) {
    const surface = runtimeFacts?.runtime_cognition_surface || runtimeFacts?.runtimeCognitionSurface || {};
    const executionSurface = surface.execution || {};
    const executionJudgmentSurface = surface.execution_judgment || surface.executionJudgment || {};
    const completionJudgmentSurface = surface.completion_judgment || surface.completionJudgment || {};
    const alignmentSurface = surface.alignment || {};
    const decisionMetadata = decision?.metadata || {};
    const runtimeMetadata = runtimeFacts?.metadata || {};
    const boundaryMetadata = executionBoundary?.metadata || {};
    const promptMode = firstNonBlank(
        decisionMetadata.prompt_mode,
        decisionMetadata.promptMode,
        runtimeMetadata.prompt_mode,
        runtimeMetadata.promptMode
    );
    const mountedRendered = booleanValue(
        decisionMetadata.mounted_context_rendered,
        decisionMetadata.mountedContextRendered,
        runtimeMetadata.mounted_context_rendered,
        runtimeMetadata.mountedContextRendered
    );
    const mountedInjected = booleanValue(
        decisionMetadata.mounted_context_injected,
        decisionMetadata.mountedContextInjected,
        runtimeMetadata.mounted_context_injected,
        runtimeMetadata.mountedContextInjected
    );
    const panelCount = numericValue(
        decisionMetadata.mounted_context_panel_count,
        decisionMetadata.mountedContextPanelCount,
        runtimeMetadata.mounted_context_panel_count,
        runtimeMetadata.mountedContextPanelCount
    );
    const nonEmptyPanelCount = numericValue(
        decisionMetadata.mounted_context_non_empty_panel_count,
        decisionMetadata.mountedContextNonEmptyPanelCount,
        runtimeMetadata.mounted_context_non_empty_panel_count,
        runtimeMetadata.mountedContextNonEmptyPanelCount
    );
    const renderedObjectCount = numericValue(
        decisionMetadata.mounted_context_rendered_object_count,
        decisionMetadata.mountedContextRenderedObjectCount,
        runtimeMetadata.mounted_context_rendered_object_count,
        runtimeMetadata.mountedContextRenderedObjectCount
    );
    const hiddenObjectCount = numericValue(
        decisionMetadata.mounted_context_hidden_object_count,
        decisionMetadata.mountedContextHiddenObjectCount,
        runtimeMetadata.mounted_context_hidden_object_count,
        runtimeMetadata.mountedContextHiddenObjectCount
    );
    const renderedSelectionTraceCount = numericValue(
        decisionMetadata.mounted_context_rendered_selection_trace_count,
        decisionMetadata.mountedContextRenderedSelectionTraceCount,
        runtimeMetadata.mounted_context_rendered_selection_trace_count,
        runtimeMetadata.mountedContextRenderedSelectionTraceCount
    );
    const hiddenSelectionTraceCount = numericValue(
        decisionMetadata.mounted_context_hidden_selection_trace_count,
        decisionMetadata.mountedContextHiddenSelectionTraceCount,
        runtimeMetadata.mounted_context_hidden_selection_trace_count,
        runtimeMetadata.mountedContextHiddenSelectionTraceCount
    );
    const budgetTruncated = booleanValue(
        decisionMetadata.mounted_context_budget_truncated,
        decisionMetadata.mountedContextBudgetTruncated,
        runtimeMetadata.mounted_context_budget_truncated,
        runtimeMetadata.mountedContextBudgetTruncated
    );
    const candidateWorkers = normalizeTextList(
        runtimeMetadata.candidate_workers,
        runtimeMetadata.candidateWorkers
    );
    const evidenceRefs = normalizeTextList(
        decisionMetadata.evidence_refs,
        decisionMetadata.evidenceRefs,
        runtimeMetadata.evidence_refs,
        runtimeMetadata.evidenceRefs,
        boundaryMetadata.evidence_refs,
        boundaryMetadata.evidenceRefs
    );
    const unfinishedItems = normalizeTextList(
        decisionMetadata.unfinished_items,
        decisionMetadata.unfinishedItems,
        runtimeMetadata.unfinished_items,
        runtimeMetadata.unfinishedItems,
        boundaryMetadata.unfinished_items,
        boundaryMetadata.unfinishedItems
    );
    const metrics = [
        promptMode ? `prompt ${humanizeToken(promptMode) || promptMode}` : null,
        mountedRendered === true ? "mounted rendered" : null,
        mountedRendered === false ? "mounted not rendered" : null,
        mountedInjected === true ? "mounted injected" : null,
        mountedInjected === false ? "mounted not injected" : null,
        panelCount ? `${panelCount} panels` : null,
        nonEmptyPanelCount ? `${nonEmptyPanelCount} non-empty` : null,
        renderedObjectCount !== null || hiddenObjectCount !== null
            ? `${renderedObjectCount ?? 0}/${hiddenObjectCount ?? 0} objects`
            : null,
        renderedSelectionTraceCount !== null || hiddenSelectionTraceCount !== null
            ? `${renderedSelectionTraceCount ?? 0}/${hiddenSelectionTraceCount ?? 0} traces`
            : null,
        budgetTruncated === true ? "budget truncated" : null
    ].filter(Boolean);
    const cognitionRows = [
        summarizeExecutionSurface(executionSurface),
        summarizeProviderRunFiles(executionSurface),
        summarizeJudgmentSurface("exec judge", executionJudgmentSurface),
        summarizeJudgmentSurface("done judge", completionJudgmentSurface)
    ].filter(Boolean);
    const alignmentChips = summarizeAlignmentSurface(alignmentSurface);
    return {
        metrics,
        candidateWorkers,
        evidenceRefs,
        unfinishedItems,
        cognitionRows,
        alignmentChips
    };
}

function stackItem(label, title, body, meta) {
    return `
        <div class="stack-item">
            <div class="stack-item__meta">
                <span class="task-badge">${escapeHtml(label || "item")}</span>
                ${meta ? `<span>${escapeHtml(meta)}</span>` : ""}
            </div>
            <strong>${escapeHtml(title || "untitled")}</strong>
            ${body ? `<p>${escapeHtml(body)}</p>` : ""}
        </div>
    `;
}

function renderArtifactCard(artifact) {
    const metadata = artifactMetadata(artifact);
    const type = artifact.artifact_type || artifact.artifactType || "artifact";
    const title = artifact.title || "untitled artifact";
    const createdAt = formatTime(artifact.created_at || artifact.createdAt);
    const worker = firstNonBlank(metadata.worker_id, metadata.workerId, metadata.selected_worker, metadata.selectedWorker);
    const status = firstNonBlank(metadata.execution_status, metadata.executionStatus, metadata.agent_run_status, metadata.agentRunStatus);
    const durationMs = metadata.duration_ms ?? metadata.durationMs;
    const threadId = firstNonBlank(metadata.provider_thread_id, metadata.providerThreadId, metadata.provider_session_id, metadata.providerSessionId);
    const agentRunId = firstNonBlank(metadata.agent_run_id, metadata.agentRunId);
    const outputText = stripAnsi(artifactOutputText(artifact, metadata));
    const summary = stripAnsi(firstNonBlank(artifact.summary, outputText, artifact.uri, "") || "");
    const chips = [
        worker ? `worker ${worker}` : null,
        status ? `status ${status}` : null,
        durationMs != null ? formatDurationMs(durationMs) : null,
        threadId ? `thread ${threadId}` : null,
        agentRunId ? `run ${agentRunId}` : null
    ].filter(Boolean);
    const files = artifactProviderRunFiles(metadata);
    const drawer = renderArtifactOutputDrawer(outputText, files);
    return `
        <div class="stack-item artifact-card">
            <div class="stack-item__meta">
                <span class="task-badge">${escapeHtml(type)}</span>
                <span>${escapeHtml(createdAt)}</span>
            </div>
            <strong>${escapeHtml(title)}</strong>
            ${chips.length > 0 ? `<div class="artifact-card__chips">${chips.map((chip) => `<span>${escapeHtml(chip)}</span>`).join("")}</div>` : ""}
            ${summary ? `<p>${escapeHtml(preview(summary, 360))}</p>` : ""}
            ${files.length > 0 ? renderArtifactProviderFiles(files) : ""}
            ${drawer}
        </div>
    `;
}

function artifactMetadata(artifact) {
    const metadata = artifact.metadata || artifact.metadata_json || artifact.metadataJson || {};
    if (typeof metadata === "string") {
        try {
            return JSON.parse(metadata);
        } catch {
            return {};
        }
    }
    return metadata && typeof metadata === "object" ? metadata : {};
}

function artifactOutputText(artifact, metadata) {
    return firstNonBlank(
        metadata.output_text,
        metadata.outputText,
        metadata.artifact_content,
        metadata.artifactContent,
        artifact.content,
        artifact.summary,
        artifact.uri
    ) || "";
}

function artifactProviderRunFiles(metadata) {
    return [
        ["run dir", metadata.provider_run_dir || metadata.providerRunDir],
        ["prompt", metadata.provider_prompt_path || metadata.providerPromptPath],
        ["events", metadata.provider_event_log_path || metadata.providerEventLogPath],
        ["last message", metadata.provider_last_message_path || metadata.providerLastMessagePath],
        ["metadata", metadata.provider_run_metadata_path || metadata.providerRunMetadataPath],
        ["codex jsonl", metadata.provider_session_log_path || metadata.providerSessionLogPath || metadata.codex_rollout_path || metadata.codexRolloutPath]
    ]
        .map(([label, path]) => ({ label, path: firstNonBlank(path) }))
        .filter((file) => file.path);
}

function renderArtifactProviderFiles(files) {
    return `
        <div class="provider-run-files artifact-card__files">
            ${files.map((file) => `
                <div class="provider-run-files__path">
                    <span class="task-badge">${escapeHtml(file.label)}</span>
                    <code>${escapeHtml(file.path)}</code>
                </div>
            `).join("")}
        </div>
    `;
}

function renderArtifactOutputDrawer(outputText, files) {
    if (!outputText) {
        return "";
    }
    const maxPreviewLength = 12000;
    const truncated = outputText.length > maxPreviewLength;
    const previewText = truncated ? `${outputText.slice(0, maxPreviewLength)}\n\n... 已截断，完整内容请查看上方 provider run 文件。` : outputText;
    const pathHint = files.find((file) => file.label === "last message" || file.label === "events" || file.label === "codex jsonl")?.path;
    return `
        <details class="inline-preview-drawer artifact-output-drawer">
            <summary class="inline-preview-drawer__summary">展开 worker 输出预览${pathHint ? ` · ${escapeHtml(preview(pathHint, 72))}` : ""}</summary>
            <pre class="provider-run-files__preview artifact-output-drawer__preview">${escapeHtml(previewText)}</pre>
        </details>
    `;
}

function stripAnsi(value) {
    return String(value || "").replace(/\u001b\[[0-9;?]*[ -/]*[@-~]/g, "");
}

function formatDurationMs(value) {
    const number = Number(value);
    if (!Number.isFinite(number)) {
        return String(value);
    }
    if (number >= 1000) {
        return `${(number / 1000).toFixed(1)}s`;
    }
    return `${Math.round(number)}ms`;
}

function renderAgentActions(actions) {
    const plan = buildAgentActionPlan(actions);
    if (!plan.hasActions) {
        return emptyState("暂无 reconciled action");
    }
    return `
        ${stackItem("summary", plan.summary, `${plan.counts.total} total`, "agent action surface")}
        ${plan.visible.map(renderAgentActionItem).join("")}
    `;
}

function renderAgentActionItem(action) {
    const statusLabel = action.status === "needs_approval" ? "needs approval" : action.status;
    const meta = [
        action.actionType,
        `risk=${action.riskLevel}`,
        action.requiresApproval ? "requires approval" : null,
        formatTime(action.createdAt)
    ].filter(Boolean).join(" · ");
    const payloadText = Object.keys(action.payload || {}).length > 0
        ? `\n${JSON.stringify(action.payload)}`
        : "";
    return stackItem(
        statusLabel,
        action.summary || action.actionType,
        `${action.rejectionReason || ""}${payloadText}`.trim(),
        meta
    );
}

function toolTraceStatusLabel(tool) {
    return buildToolTraceStatusLabel(tool);
}

function toolTraceSummary(tool) {
    return buildToolTraceSummary(tool, { preview });
}

function toolTraceMeta(tool) {
    return [
        formatTime(tool?.created_at || tool?.createdAt),
        tool?.elapsed_ms || tool?.elapsedMs ? `${tool.elapsed_ms || tool.elapsedMs} ms` : null
    ].filter(Boolean).join(" · ");
}

function buildUserMessage(task) {
    return preview(
        firstNonBlank(task.intent, task.metadata?.intent, task.goal, task.title, task.id) || task.id,
        280
    );
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

function buildTaskFocusLineBase(task, flow) {
    const taskStatus = firstNonBlank(task?.status, "active");
    const controlNode = firstNonBlank(task?.control_node, task?.controlNode, "intake");
    const taskMetadata = flow?.task?.metadata || task?.metadata || {};
    const recoveryStage = firstNonBlank(taskMetadata.recovery_stage, taskMetadata.recoveryStage);
    const autoHandoffTarget = firstNonBlank(taskMetadata.auto_handoff_target, taskMetadata.autoHandoffTarget);
    if (taskStatus.toLowerCase() === "active" && controlNode === "scheduler" && recoveryStage === "auto_handoff_scheduled" && autoHandoffTarget) {
        return `${taskStatus} / ${controlNode} / handoff queued`;
    }
    return `${taskStatus} / ${controlNode}`;
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

function latestTaskOutcomeMessage(task, flow) {
    const taskId = firstNonBlank(task?.id);
    if (!taskId) {
        return null;
    }
    const candidates = [
        ...(Array.isArray(flow?.related_messages) ? flow.related_messages : []),
        ...(Array.isArray(flow?.relatedMessages) ? flow.relatedMessages : []),
        ...state.relatedMessages,
        ...state.messages
    ];
    const outcomeTypes = new Set(["task_progress", "task_result", "worker_round"]);
    const matched = candidates.filter((message) =>
        messageTaskId(message) === taskId
        && outcomeTypes.has(normalizeMessageType(message?.message_type || message?.messageType))
    );
    if (matched.length === 0) {
        return null;
    }
    return matched
        .slice()
        .sort((left, right) => timestampMs(left?.created_at || left?.createdAt || 0) - timestampMs(right?.created_at || right?.createdAt || 0))
        .at(-1) || null;
}

function latestTaskOutcomeNarrative(task, flow, max = 320) {
    const latestOutcome = latestTaskOutcomeMessage(task, flow);
    const outcomeMetadata = latestOutcome?.metadata || {};
    const taskMetadata = flow?.task?.metadata || task?.metadata || {};
    const latestNarrative = firstNonBlank(
        latestOutcome?.content,
        outcomeMetadata.content,
        outcomeMetadata.summary_text,
        outcomeMetadata.summaryText,
        outcomeMetadata.summary_preview,
        outcomeMetadata.summaryPreview,
        failureNarrativeFallback(taskMetadata)
    );
    return latestNarrative ? preview(latestNarrative, max) : "";
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

function effectiveTaskOutcomeFullContent(task, flow) {
    const latestOutcome = latestTaskOutcomeMessage(task, flow);
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
    const latestOutcome = latestTaskOutcomeMessage(task, flow);
    const outcomeMetadata = latestOutcome?.metadata || {};
    const taskMetadata = flow?.task?.metadata || task?.metadata || {};
    const judgmentTrace = flow?.judgment_trace || flow?.judgmentTrace || {};
    const latest = firstNonBlank(
        effectiveTaskOutcomeFullContent(task, flow),
        failureNarrativeFallback(outcomeMetadata),
        failureNarrativeFallback(taskMetadata),
        outcomeMetadata.summary_preview,
        outcomeMetadata.summaryPreview,
        judgmentTrace.latest_output,
        judgmentTrace.latestOutput,
        flow?.runtime_context?.active_context?.continuity_summary,
        flow?.runtimeContext?.activeContext?.continuitySummary,
        task?.summary,
        task?.next_step,
        task?.nextStep
    );
    return latest ? preview(latest, max) : "";
}

function assistantOutputFullContent(task, flow) {
    const latestOutcome = latestTaskOutcomeMessage(task, flow);
    const outcomeMetadata = latestOutcome?.metadata || {};
    return firstNonBlank(
        effectiveTaskOutcomeFullContent(task, flow),
        outcomeMetadata.failure_summary_readable,
        outcomeMetadata.failureSummaryReadable,
        outcomeMetadata.output_text,
        outcomeMetadata.outputText,
        outcomeMetadata.artifact_content,
        outcomeMetadata.artifactContent,
        latestOutcome?.content
    );
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

function buildAssistantMessage(task, flow) {
    const activeContext = flow?.runtime_context?.active_context || flow?.runtimeContext?.activeContext || {};
    const taskMetadata = flow?.task?.metadata || task?.metadata || {};
    const judgmentTrace = flow?.judgment_trace || flow?.judgmentTrace || {};
    const executionJudgment = judgmentTrace.execution_judgment || judgmentTrace.executionJudgment || {};
    const completionJudgment = judgmentTrace.completion_judgment || judgmentTrace.completionJudgment || {};
    return preview(
        firstNonBlank(
            latestTaskOutcomeNarrative(task, flow, 320),
            failureNarrativeFallback(taskMetadata),
            activeContext.continuity_summary,
            activeContext.continuitySummary,
            assistantOutputPreview(task, flow, 320),
            task.summary,
            completionJudgment.summary,
            executionJudgment.summary,
            task.next_step,
            task.nextStep,
            "任务已进入 harness，等待继续推进。"
        ),
        320
    );
}

function buildAssistantSignals(task, flow) {
    const judgmentTrace = flow?.judgment_trace || flow?.judgmentTrace || {};
    const routePreview = flow?.route_preview || flow?.routePreview || {};
    const latestPacket = flow?.latest_packet || flow?.latestPacket || {};
    const executionJudgment = judgmentTrace.execution_judgment || judgmentTrace.executionJudgment || {};
    const completionJudgment = judgmentTrace.completion_judgment || judgmentTrace.completionJudgment || {};
    const taskMetadata = flow?.task?.metadata || task?.metadata || {};
    const recoveryDetail = messageCardRecoveryDetail(taskMetadata, true);
    return [
        valueLine("action", judgmentTrace.recommended_action || judgmentTrace.recommendedAction || executionJudgment.metadata?.action),
        valueLine("completion", completionJudgment.metadata?.completion_status || completionJudgment.metadata?.status),
        valueLine("recovery", recoveryDetail),
        valueLine("route", routeSignal(flow)),
        valueLine("tools", toolChainLabel(flow)),
        valueLine("packet", latestPacket.active_task_summary || latestPacket.activeTaskSummary)
    ].filter(Boolean).slice(0, 4);
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

function buildFollowupDraft(task, flow) {
    const judgmentTrace = flow?.judgment_trace || flow?.judgmentTrace || {};
    const recommendedNextStep = judgmentTrace.recommended_next_step || judgmentTrace.recommendedNextStep;
    const nextStep = firstNonBlank(recommendedNextStep, task.next_step, task.nextStep, task.summary);
    const latest = firstNonBlank(buildAssistantMessage(task, flow), task.summary, latestOutput(flow));
    const taskType = firstNonBlank(task.task_type, task.taskType, task.metadata?.task_type, task.metadata?.taskType, dom.taskType.value) || "continuation";
    const priority = firstNonBlank(task.priority, dom.taskPriority.value) || "high";
    const goal = firstNonBlank(task.goal, `继续推进 ${task.title || task.id}`);
    return {
        title: deriveTitle(`跟进：${task.title || task.id}`),
        taskType,
        priority,
        goal,
        intent: [
            `基于当前任务继续推进：${task.title || task.id}。`,
            latest ? `当前进展：${preview(latest, 180)}` : null,
            nextStep ? `优先处理：${preview(nextStep, 180)}` : "请先判断下一步，再继续推进。",
            "保持和上一轮产物一致，不要重复从零开始。"
        ].filter(Boolean).join("\n")
    };
}

function renderComposerContext() {
    const task = selectedTask();
    const followupParent = followupSourceTask();
    const contextTask = followupParent || task;
    const session = currentSession();
    const sessionClosed = isClosedSession(session);
    const plan = composerSubmissionPlan();
    dom.composerSessionLabel.textContent = session?.title || "自动创建";
    renderComposerRecovery(sessionClosed);
    syncComposerModeSwitch();
    if (!contextTask) {
        if (dom.composerTaskHint) {
            dom.composerTaskHint.textContent = sessionClosed
                ? "当前 session 已关闭。请新建会话后继续。"
                : plan.resolvedMode === "task"
                    ? `当前会直接发布新任务。原因：${plan.reasonLabel}。`
                    : "当前没有绑定任务；默认先继续 thread。";
        }
        if (dom.composerInlineState) {
            dom.composerInlineState.innerHTML = sessionClosed
                ? `<span class="signal signal--warn">closed session 不接受新消息或新任务。先新建一个 session，再继续。</span>`
                : composerInlineSignals(null, null, false, plan);
        }
        if (dom.followupButton) {
            dom.followupButton.disabled = true;
        }
        if (dom.clearFollowupButton) {
            dom.clearFollowupButton.disabled = true;
        }
        syncComposerSecondaryActions(task, followupParent, sessionClosed);
        if (dom.submitTaskButton) {
            dom.submitTaskButton.disabled = sessionClosed;
            dom.submitTaskButton.textContent = submitTaskButtonLabel();
        }
        return;
    }

    const flow = contextTask.id === state.selectedTaskId ? state.liveFlow : null;
    const judgmentTrace = flow?.judgment_trace || flow?.judgmentTrace || {};
    const nextStep = firstNonBlank(
        judgmentTrace.recommended_next_step || judgmentTrace.recommendedNextStep,
        contextTask.next_step,
        contextTask.nextStep,
        contextTask.summary
    );
    const selectedLine = task
        ? `${task.title || task.id} · ${firstNonBlank(task.status, "active")}/${firstNonBlank(task.control_node, task.controlNode, "intake")}`
        : null;
    const followupLine = followupParent
        ? `follow-up of ${followupParent.title || followupParent.id}`
        : null;
    const nextLine = nextStep ? `next · ${preview(nextStep, 64)}` : null;

    if (dom.composerTaskHint) {
        dom.composerTaskHint.textContent = firstNonBlank(
            followupLine ? `${selectedLine} · ${followupLine}` : null,
            selectedLine,
            nextLine,
            "继续当前 thread。"
        );
    }
    if (dom.composerInlineState) {
        dom.composerInlineState.innerHTML = composerInlineSignals(task, followupParent, sessionClosed, plan);
    }
    if (dom.followupButton) {
        dom.followupButton.disabled = !task || sessionClosed;
    }
    if (dom.clearFollowupButton) {
        dom.clearFollowupButton.disabled = !followupParent;
    }
    syncComposerSecondaryActions(task, followupParent, sessionClosed);
    if (dom.submitTaskButton) {
        dom.submitTaskButton.disabled = sessionClosed;
        dom.submitTaskButton.textContent = submitTaskButtonLabel();
    }
}

function renderComposerRecovery(sessionClosed) {
    if (!dom.composerRecovery) {
        return;
    }
    if (!sessionClosed) {
        dom.composerRecovery.innerHTML = "";
        return;
    }
    dom.composerRecovery.innerHTML = `
        <button class="button button--ghost composer-recovery__button" type="button" data-composer-recovery="new-session">
            新建会话并继续
        </button>
    `;
}

function syncComposerModeSwitch() {
    if (!dom.composerModeSwitch) {
        return;
    }
    dom.composerModeSwitch.querySelectorAll("[data-composer-mode]").forEach((button) => {
        button.classList.toggle("is-active", button.dataset.composerMode === state.composerMode);
    });
}

function composerInlineSignals(task, followupParent, sessionClosed, plan = composerSubmissionPlan()) {
    return renderComposerInlineSignalsHtml({
        sessionClosed,
        facadeReply: scopedLastFacadeReply(task),
        plan,
        task,
        followupParent
    }, {
        escapeHtml,
        preview
    });
}

function scopedLastFacadeReply(task) {
    return scopedFacadeReply(
        state.lastFacadeReply,
        state.selectedSessionId || "",
        task?.id || state.selectedTaskId || ""
    );
}

function submitTaskButtonLabel() {
    switch (composerSubmissionPlan().resolvedMode) {
        case "followup":
            return "发布 follow-up";
        case "task":
            return "发布任务";
        default:
            return "发送";
    }
}

async function submitComposerThroughChatFacade(intent, plan = composerSubmissionPlan(), submitContext = null, ensuredSession = null) {
    const session = ensuredSession || await ensureSessionForMessage(intent);
    const context = submitContext || buildComposerSubmitContext({
        planResolvedMode: plan.resolvedMode,
        selectedTaskId: selectedTask()?.id || "",
        selectedTaskStatus: selectedTask()?.status || "",
        followupParentTaskId: state.followupParentTaskId,
        continueCurrentChecked: dom.taskContinueCurrent.checked
    });
    const goal = dom.taskGoal.value.trim() || null;
    const title = dom.taskTitle.value.trim() || null;
    const assignedWorker = dom.taskAssignedWorker.value.trim() || null;
    const modelMode = dom.taskModelMode.value.trim() || null;
    const autoStart = dom.taskAutoStart.checked;
    const autoMultiRound = dom.taskAutoMultiRound?.checked || false;
    const localPaths = splitComposerLines(dom.taskLocalPaths?.value);
    const validationCommands = splitComposerLines(dom.taskValidationCommands?.value);
    const executionContractLines = splitComposerLines(dom.taskExecutionContract?.value);
    const writeScope = executionContractLines.length > 0 ? [executionContractLines[0]] : [];
    const acceptanceCriteria = executionContractLines.slice(writeScope.length);

    return requestFacadeCompletion(state.facadeSurface, buildFacadeRequest({
        intent,
        sessionId: session.id,
        facadeModel: facadeModelForComposer(),
        facadeSurface: state.facadeSurface,
        taskMode: context.taskMode,
        title,
        derivedTitle: deriveTitle(intent),
        goal,
        assignedWorker,
        modelMode,
        followupParentTaskId: context.followupParentTaskId,
        referencedTaskId: context.referencedTaskId,
        continueCurrentTaskId: context.continueCurrentTaskId,
        taskType: dom.taskType.value,
        taskPriority: dom.taskPriority.value,
        autoStart,
        autoMultiRound,
        localPaths,
        validationCommands,
        writeScope,
        acceptanceCriteria
    }));
}

function splitComposerLines(value) {
    if (typeof value !== "string" || !value.trim()) {
        return [];
    }
    return value
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter(Boolean);
}

async function waitForPendingAutoTaskSelection(sessionId) {
    const tracker = state.pendingAutoTaskTracker;
    if (!tracker?.shouldTrack || tracker.sessionId !== sessionId) {
        return;
    }
    for (let attempt = 0; attempt < 20; attempt += 1) {
        const selectedTaskId = await tryCatchUpPendingAutoTaskSelection(sessionId);
        if (selectedTaskId) {
            return;
        }
        await delay(250);
    }
}

async function tryCatchUpPendingAutoTaskSelection(sessionId) {
    const tracker = state.pendingAutoTaskTracker;
    if (!tracker?.shouldTrack || tracker.sessionId !== sessionId) {
        return "";
    }
    try {
        const tasks = await api(`/api/v1/sessions/${encodeURIComponent(sessionId)}/tasks`);
        const candidateTaskId = resolvePendingAutoTaskCandidate({
            tracker,
            currentSessionId: sessionId,
            tasks
        });
        if (!candidateTaskId) {
            return "";
        }
        state.tasks = tasks
            .slice()
            .sort((a, b) => timestampMs(a.created_at || a.createdAt || 0) - timestampMs(b.created_at || b.createdAt || 0));
        state.selectedSessionId = sessionId;
        state.selectedTaskId = candidateTaskId;
        state.selectedTaskStickyUntil = Date.now() + 10000;
        state.pendingAutoTaskTracker = null;
        renderThread();
        renderComposerContext();
        renderMessageComposerContext();
        syncLocationSelection();
        await loadSelectedTask(candidateTaskId, false);
        return candidateTaskId;
    } catch (error) {
        console.warn("pending auto-task catch-up failed", error);
        return "";
    }
}

async function applyChatFacadeCompletion(completion, intent, plan = composerSubmissionPlan()) {
    const agentcloud = completion?.agentcloud || {};
    const sessionId = firstNonBlank(agentcloud.session_id, agentcloud.sessionId, state.selectedSessionId);
    const taskId = firstNonBlank(agentcloud.task_id, agentcloud.taskId);
    const taskStatus = firstNonBlank(agentcloud.task_status, agentcloud.taskStatus);
    const replyType = firstNonBlank(agentcloud.reply_type, agentcloud.replyType);
    const replySource = firstNonBlank(agentcloud.reply_source, agentcloud.replySource);
    const referencedTask = resolveComposerReferencedTask();
    const replyFeedback = buildFacadeReplyFeedback({
        resolvedMode: plan.resolvedMode,
        replyType,
        replySource,
        sessionId,
        taskId,
        taskStatus,
        intent,
        referencedTaskTitle: referencedTask?.title || referencedTask?.id || ""
    });
    dom.taskTitle.value = "";
    dom.taskGoal.value = "";
    dom.taskIntent.value = "";
    dom.taskAutoStart.checked = true;
    dom.taskContinueCurrent.checked = false;
    dom.taskAssignedWorker.value = "";
    dom.taskModelMode.value = "";
    state.followupParentTaskId = null;

    if (sessionId) {
        state.selectedSessionId = sessionId;
    }
    state.lastFacadeReply = replyFeedback;
    if (taskId) {
        state.selectedTaskId = taskId;
        state.selectedTaskStickyUntil = Date.now() + 10000;
    }
    await loadSessions();
    await loadTasks();
    if (sessionId) {
        await loadMessages(sessionId);
    } else {
        await loadMessages();
    }

    if (taskId) {
        state.pendingAutoTaskTracker = null;
        await selectTask(taskId, false);
    } else {
        state.relatedMessages = [];
        renderDetails();
    }

    renderComposerContext();
    renderMessageComposerContext();
    showToast(replyFeedback.toastText);
}

function composerTaskMode(plan = composerSubmissionPlan()) {
    if (plan.resolvedMode === "message") {
        return "task_auto";
    }
    if (shouldContinueCurrentTask(plan)) {
        return "task_required";
    }
    return "task_required";
}

function shouldContinueCurrentTask(plan = composerSubmissionPlan()) {
    return plan.resolvedMode === "task"
        && dom.taskContinueCurrent.checked
        && !state.followupParentTaskId
        && Boolean(selectedTask());
}

function resolveComposerReferencedTask() {
    const plan = composerSubmissionPlan();
    const task = selectedTask();
    if (shouldContinueCurrentTask(plan) && task && !isTerminalTask(task)) {
        return task;
    }
    if (plan.resolvedMode === "message" && task && !isTerminalTask(task)) {
        return task;
    }
    return null;
}

function isTerminalTask(task) {
    const status = firstNonBlank(task?.status, "");
    if (!status) {
        return false;
    }
    const normalized = status.toLowerCase();
    return normalized === "done" || normalized === "failed";
}

function facadeModelForComposer() {
    switch (dom.taskModelMode.value) {
        case "strong_only":
            return "agentcloud-strong";
        case "small_only":
            return "agentcloud-fast";
        default:
            return "agentcloud-default";
    }
}

function composerSubmissionPlan() {
    return buildComposerSubmissionPlan({
        composerMode: state.composerMode,
        followupParentTaskId: state.followupParentTaskId,
        advancedOpen: Boolean(dom.composerAdvanced?.open),
        taskTitle: dom.taskTitle.value,
        taskGoal: dom.taskGoal.value,
        taskAssignedWorker: dom.taskAssignedWorker.value,
        taskModelMode: dom.taskModelMode.value,
        taskContinueCurrent: dom.taskContinueCurrent.checked,
        taskAutoStart: dom.taskAutoStart.checked,
        taskType: dom.taskType.value,
        taskPriority: dom.taskPriority.value
    });
}

async function ensureSessionForMessage(content) {
    if (state.selectedSessionId) {
        return currentSession() || { id: state.selectedSessionId };
    }

    const session = await api("/api/v1/sessions", {
        method: "POST",
        body: JSON.stringify({
            title: deriveTitle(content) || "dialogue session"
        })
    });
    state.selectedSessionId = session.id;
    return session;
}

function currentSession() {
    return state.sessions.find((session) => session.id === state.selectedSessionId) || null;
}

function selectedTask() {
    return state.tasks.find((item) => item.id === state.selectedTaskId) || state.liveFlow?.task || null;
}

function followupSourceTask() {
    if (!state.followupParentTaskId) {
        return null;
    }
    return state.tasks.find((item) => item.id === state.followupParentTaskId) || null;
}

function latestOutput(flow) {
    const judgmentTrace = flow?.judgment_trace || flow?.judgmentTrace || {};
    return judgmentTrace.latest_output || judgmentTrace.latestOutput || null;
}

function taskSessionId(task) {
    return firstNonBlank(task?.session_id, task?.sessionId);
}

function messageTaskId(message) {
    return firstNonBlank(message?.task_id, message?.taskId);
}

function experimentRunView(flow) {
    return flow?.experiment_run || flow?.experimentRun || {};
}

function experimentRunMetadata(flow) {
    const experimentRun = experimentRunView(flow);
    return experimentRun.metadata || {};
}

async function loadTaskExperimentSummary(taskId, flow) {
    const experimentRun = experimentRunView(flow);
    const experimentName = firstNonBlank(
        experimentRun.experiment_name,
        experimentRun.experimentName
    );
    if (!experimentName) {
        return null;
    }
    try {
        return await api(`/api/v1/tasks/${encodeURIComponent(taskId)}/experiment_summary`);
    } catch (error) {
        console.warn("experiment summary unavailable", error);
        return null;
    }
}

function renderRouteBox(flow, task) {
    const routePreview = flow?.route_preview || flow?.routePreview || {};
    const cognitionSurface = flow?.runtime_cognition_surface || flow?.runtimeCognitionSurface || {};
    const cognitionTimeline = flow?.runtime_cognition_timeline || flow?.runtimeCognitionTimeline || [];
    const routeSurface = cognitionSurface.route || {};
    const experimentRun = experimentRunView(flow);
    const metadata = experimentRunMetadata(flow);
    const selectedWorker = firstNonBlank(
        routeSurface.selected_worker,
        routeSurface.selectedWorker,
        routePreview.selected_worker,
        routePreview.selectedWorker,
        task?.assigned_worker,
        task?.assignedWorker,
        "unassigned"
    );
    const routeSource = firstNonBlank(
        routeSurface.route_source,
        routeSurface.routeSource,
        routePreview.route_source,
        routePreview.routeSource,
        metadata.route_source,
        metadata.routeSource,
        "router"
    );
    const taskType = firstNonBlank(
        routePreview.task_type,
        routePreview.taskType,
        experimentRun.task_type,
        experimentRun.taskType,
        task?.metadata?.task_type,
        task?.metadata?.taskType,
        "general"
    );
    const modelMode = firstNonBlank(
        experimentRun.model_mode,
        experimentRun.modelMode,
        metadata.model_mode,
        metadata.modelMode
    );
    const preferredWorkerHint = firstNonBlank(
        routeSurface.preferred_worker_hint,
        routeSurface.preferredWorkerHint,
        routePreview.preferred_worker_hint,
        routePreview.preferredWorkerHint,
        metadata.preferred_worker_hint,
        metadata.preferredWorkerHint
    );
    const fallbackReason = firstNonBlank(
        metadata.fallback_reason,
        metadata.fallbackReason
    );
    const learningHintApplied = booleanValue(
        routeSurface.learning_hint_applied,
        routeSurface.learningHintApplied,
        routePreview.learning_hint_applied,
        routePreview.learningHintApplied,
        metadata.learning_hint_applied,
        metadata.learningHintApplied
    );
    const candidateWorkers = normalizeTextList(
        routeSurface.candidate_workers,
        routeSurface.candidateWorkers,
        routePreview.candidate_workers,
        routePreview.candidateWorkers
    );
    const routeReason = firstNonBlank(
        routeSurface.route_reason,
        routeSurface.routeReason,
        routePreview.route_reason,
        routePreview.routeReason,
        routePreview.reason,
        routePreview.summary,
        fallbackReason
    );
    const routeAlignment = booleanValue(
        cognitionSurface?.alignment?.route_worker_matches_execution_worker,
        cognitionSurface?.alignment?.routeWorkerMatchesExecutionWorker
    );
    const providerDeprioritization = buildProviderDeprioritizationPlan(
        routePreview.recovery_unpinned_recommendation
        || routePreview.recoveryUnpinnedRecommendation
        || routePreview
    );
    const recoveryExecutionMode = firstNonBlank(
        routePreview.recovery_execution_mode,
        routePreview.recoveryExecutionMode,
        routePreview.recovery_unpinned_recommendation?.recovery_execution_mode,
        routePreview.recoveryUnpinnedRecommendation?.recovery_execution_mode
    );
    const routeChips = [
        modelMode ? `mode: ${humanizeToken(modelMode) || modelMode}` : null,
        preferredWorkerHint ? `hint: ${preferredWorkerHint}` : null,
        learningHintApplied === true ? "learning: applied" : null,
        learningHintApplied === false ? "learning: observed, not applied" : null,
        routeAlignment === true ? "route/execution aligned" : null,
        routeAlignment === false ? "route/execution diverged" : null,
        recoveryExecutionMode === "fresh_session" ? "recovery: fresh session" : null,
        providerDeprioritization.chip || null
    ].filter(Boolean);
    if (!selectedWorker && !routeReason && candidateWorkers.length === 0 && routeChips.length === 0) {
        return emptyState("暂无 route preview");
    }
    const routePlan = buildRouteBoxPlan({
        selectedWorker,
        routeSource,
        routeReason,
        taskType,
        candidateWorkers,
        routeChips,
        providerDeprioritization,
        cognitionTimeline
    });
    return `
        <div class="route-box">
            <div class="stack-item__meta">
                <span class="task-badge">${escapeHtml(routePlan.worker)}</span>
                <span>${escapeHtml(routePlan.routeSource)}</span>
                ${routePlan.taskType ? `<span>${escapeHtml(routePlan.taskType)}</span>` : ""}
            </div>
            ${routePlan.routeReason ? `<strong>${escapeHtml(preview(routePlan.routeReason, 220))}</strong>` : ""}
            ${routePlan.providerDeprioritization?.providerDeprioritized ? `
                <p class="route-box__recovery-note">
                    <strong>${escapeHtml(routePlan.providerDeprioritization.headline)}</strong>
                    ${routePlan.providerDeprioritization.detail ? `<span>${escapeHtml(routePlan.providerDeprioritization.detail)}</span>` : ""}
                </p>
            ` : ""}
            ${routePlan.hasDrawer ? `
                <details class="route-box__drawer">
                    <summary class="route-box__summary">${escapeHtml(routePlan.drawerSummary)}</summary>
                    <div class="route-box__body">
                        ${routePlan.candidateWorkers.length > 0 ? `<p class="mono">${escapeHtml(routePlan.candidateWorkers.join(", "))}</p>` : ""}
                        ${routePlan.routeChips.length > 0 ? `
                            <div class="chip-list experiment-summary__chips">
                                ${routePlan.routeChips.map((chip) => `<span class="chip">${escapeHtml(chip)}</span>`).join("")}
                            </div>
                        ` : ""}
                        ${renderCognitionTimeline(routePlan.cognitionTimeline)}
                    </div>
                </details>
            ` : ""}
        </div>
    `;
}

function renderExperimentSummary(flow, summary) {
    const experimentRun = experimentRunView(flow);
    const metadata = experimentRunMetadata(flow);
    const experimentName = firstNonBlank(
        experimentRun.experiment_name,
        experimentRun.experimentName,
        metadata.experiment_name,
        metadata.experimentName
    );
    if (!experimentName) {
        return emptyState("当前任务不属于 experiment batch。");
    }

    const currentMode = firstNonBlank(
        experimentRun.model_mode,
        experimentRun.modelMode,
        metadata.model_mode,
        metadata.modelMode,
        "orchestrated"
    );
    const currentWorkerPromptMode = firstNonBlank(
        experimentRun.prompt_mode,
        experimentRun.promptMode,
        metadata.prompt_mode,
        metadata.promptMode
    );
    const currentExecutionJudgmentPromptMode = firstNonBlank(
        experimentRun.execution_judgment_prompt_mode,
        experimentRun.executionJudgmentPromptMode,
        metadata.execution_judgment_prompt_mode,
        metadata.executionJudgmentPromptMode,
        currentWorkerPromptMode
    );
    const currentCompletionJudgmentPromptMode = firstNonBlank(
        experimentRun.completion_judgment_prompt_mode,
        experimentRun.completionJudgmentPromptMode,
        metadata.completion_judgment_prompt_mode,
        metadata.completionJudgmentPromptMode,
        currentWorkerPromptMode
    );
    const taskCaseKey = firstNonBlank(
        experimentRun.task_case_key,
        experimentRun.taskCaseKey,
        metadata.task_case_key,
        metadata.taskCaseKey
    );
    const acceptanceResult = firstNonBlank(
        experimentRun.acceptance_result,
        experimentRun.acceptanceResult,
        "not_evaluated"
    );
    const taskLengthBucket = firstNonBlank(
        experimentRun.task_length_bucket,
        experimentRun.taskLengthBucket,
        metadata.task_length_bucket,
        metadata.taskLengthBucket,
        "unspecified"
    );
    const modeSummaries = summary?.mode_summaries || summary?.modeSummaries || [];
    const promptModeSummaries = summary?.prompt_mode_summaries || summary?.promptModeSummaries || {};
    const executionJudgmentPromptModeSummaries =
        summary?.execution_judgment_prompt_mode_summaries
        || summary?.executionJudgmentPromptModeSummaries
        || {};
    const completionJudgmentPromptModeSummaries =
        summary?.completion_judgment_prompt_mode_summaries
        || summary?.completionJudgmentPromptModeSummaries
        || {};
    const supportedModes = summary?.supported_modes || summary?.supportedModes || [];
    const caseComparisons = summary?.case_comparisons || summary?.caseComparisons || [];
    const currentCase = taskCaseKey
        ? caseComparisons.find((item) => firstNonBlank(item.task_case_key, item.taskCaseKey) === taskCaseKey)
        : null;
    const summaryChips = [
        `mode: ${humanizeToken(currentMode) || currentMode}`,
        taskCaseKey ? `case: ${taskCaseKey}` : null,
        `acceptance: ${humanizeToken(acceptanceResult) || acceptanceResult}`,
        `bucket: ${humanizeToken(taskLengthBucket) || taskLengthBucket}`,
        summary ? `runs: ${String(numberOrNull(summary.total_runs, summary.totalRuns) ?? 0)}` : null
    ].filter(Boolean);
    const experimentPlan = buildExperimentSummaryPlan({
        experimentName,
        taskLabel: firstNonBlank(experimentRun.task_title, experimentRun.taskTitle, taskCaseKey, "current task"),
        summaryChips,
        modeSummaries,
        currentMode,
        currentCase,
        supportedModes,
        promptModeSummaries,
        executionJudgmentPromptModeSummaries,
        completionJudgmentPromptModeSummaries
    });
    return `
        <div class="experiment-summary">
            <div class="stack-item__meta">
                <span class="task-badge">${escapeHtml(experimentPlan.experimentName)}</span>
                <span>${escapeHtml(experimentPlan.taskLabel)}</span>
            </div>
            <div class="chip-list experiment-summary__chips">
                ${experimentPlan.summaryChips.map((chip) => `<span class="chip">${escapeHtml(chip)}</span>`).join("")}
            </div>
            ${summary ? `
                ${experimentPlan.currentModeCard ? `
                    <div class="experiment-summary__headline">
                        ${renderExperimentModeCard(experimentPlan.currentModeCard, currentMode)}
                    </div>
                ` : ""}
                ${experimentPlan.hasDrawer ? `
                    <details class="experiment-summary__drawer">
                        <summary class="experiment-summary__drawer-summary">${escapeHtml(experimentPlan.drawerSummary)}</summary>
                        <div class="experiment-summary__drawer-body">
                            ${experimentPlan.comparisonModeCards.length > 0 ? `
                                <div class="experiment-summary__grid">
                                    ${experimentPlan.comparisonModeCards
                                        .map((mode) => renderExperimentModeCard(mode, currentMode))
                                        .join("")}
                                </div>
                            ` : ""}
                            ${renderExperimentPromptModeComparisonSection(
                                promptModeSummaries,
                                executionJudgmentPromptModeSummaries,
                                completionJudgmentPromptModeSummaries,
                                currentWorkerPromptMode,
                                currentExecutionJudgmentPromptMode,
                                currentCompletionJudgmentPromptMode
                            )}
                            ${experimentPlan.hasCaseComparison ? `
                                <div class="experiment-summary__case-grid">
                                    ${experimentPlan.caseModes
                                        .map((mode) => renderExperimentCaseCard(mode, currentCase, currentMode))
                                        .join("")}
                                </div>
                            ` : emptyState("当前 task case 还没有三种 mode 对比。")}
                        </div>
                    </details>
                ` : ""}
            ` : `
                ${emptyState("实验 run 已识别，但聚合 summary 暂时不可用。")}
            `}
        </div>
    `;
}

function renderExperimentModeCard(modeSummary, currentMode) {
    const modelMode = firstNonBlank(modeSummary?.model_mode, modeSummary?.modelMode, "unknown");
    const acceptanceRate = numberOrNull(modeSummary?.acceptance_rate, modeSummary?.acceptanceRate) ?? 0;
    const completionRate = numberOrNull(modeSummary?.completion_rate, modeSummary?.completionRate) ?? 0;
    const learningHintAppliedRate = numberOrNull(
        modeSummary?.learning_hint_applied_rate,
        modeSummary?.learningHintAppliedRate
    ) ?? 0;
    const averageToolChainStepCount = numberOrNull(
        modeSummary?.average_tool_chain_step_count,
        modeSummary?.averageToolChainStepCount
    ) ?? 0;
    const routeSourceCounts = modeSummary?.route_source_counts || modeSummary?.routeSourceCounts || {};
    const workerPromptModeCounts = modeSummary?.prompt_mode_counts || modeSummary?.promptModeCounts || {};
    const workerPromptModeSampleCount = numberOrNull(
        modeSummary?.runs_with_prompt_mode_data,
        modeSummary?.runsWithPromptModeData
    ) ?? 0;
    const workerMountedRenderedRate = numberOrNull(
        modeSummary?.mounted_context_rendered_rate,
        modeSummary?.mountedContextRenderedRate
    );
    const workerMountedInjectedRate = numberOrNull(
        modeSummary?.mounted_context_injected_rate,
        modeSummary?.mountedContextInjectedRate
    );
    const averageMountedContextPanelCount = numberOrNull(
        modeSummary?.average_mounted_context_panel_count,
        modeSummary?.averageMountedContextPanelCount
    );
    const executionJudgmentPromptModeCounts =
        modeSummary?.execution_judgment_prompt_mode_counts || modeSummary?.executionJudgmentPromptModeCounts || {};
    const executionJudgmentPromptModeSampleCount = numberOrNull(
        modeSummary?.runs_with_execution_judgment_prompt_mode_data,
        modeSummary?.runsWithExecutionJudgmentPromptModeData
    ) ?? 0;
    const executionJudgmentMountedRenderedRate = numberOrNull(
        modeSummary?.execution_judgment_mounted_context_rendered_rate,
        modeSummary?.executionJudgmentMountedContextRenderedRate
    );
    const executionJudgmentMountedInjectedRate = numberOrNull(
        modeSummary?.execution_judgment_mounted_context_injected_rate,
        modeSummary?.executionJudgmentMountedContextInjectedRate
    );
    const completionJudgmentPromptModeCounts =
        modeSummary?.completion_judgment_prompt_mode_counts || modeSummary?.completionJudgmentPromptModeCounts || {};
    const completionJudgmentPromptModeSampleCount = numberOrNull(
        modeSummary?.runs_with_completion_judgment_prompt_mode_data,
        modeSummary?.runsWithCompletionJudgmentPromptModeData
    ) ?? 0;
    const completionJudgmentMountedRenderedRate = numberOrNull(
        modeSummary?.completion_judgment_mounted_context_rendered_rate,
        modeSummary?.completionJudgmentMountedContextRenderedRate
    );
    const completionJudgmentMountedInjectedRate = numberOrNull(
        modeSummary?.completion_judgment_mounted_context_injected_rate,
        modeSummary?.completionJudgmentMountedContextInjectedRate
    );
    const isCurrent = modelMode === currentMode ? " is-current" : "";
    return `
        <div class="experiment-mode-card${isCurrent}">
            <div class="stack-item__meta">
                <span class="task-badge" data-tone="${modelMode === currentMode ? "active" : "default"}">${escapeHtml(modelMode)}</span>
                <span>${escapeHtml(String(numberOrNull(modeSummary?.run_count, modeSummary?.runCount) ?? 0))} runs</span>
                <span>${escapeHtml(formatRate(acceptanceRate))} accept</span>
            </div>
            <strong>${escapeHtml(`${formatRate(completionRate)} done · ${formatRate(learningHintAppliedRate)} learned hint applied`)}</strong>
            <p>${escapeHtml(`${formatDecimal(averageToolChainStepCount)} avg tool steps · ${summarizeCountMap(routeSourceCounts)}`)}</p>
            <div class="experiment-rollout-grid">
                ${renderExperimentRolloutBlock("worker", workerPromptModeCounts, workerPromptModeSampleCount, workerMountedRenderedRate, workerMountedInjectedRate, averageMountedContextPanelCount)}
                ${renderExperimentRolloutBlock("exec judge", executionJudgmentPromptModeCounts, executionJudgmentPromptModeSampleCount, executionJudgmentMountedRenderedRate, executionJudgmentMountedInjectedRate)}
                ${renderExperimentRolloutBlock("done judge", completionJudgmentPromptModeCounts, completionJudgmentPromptModeSampleCount, completionJudgmentMountedRenderedRate, completionJudgmentMountedInjectedRate)}
            </div>
        </div>
    `;
}

function renderExperimentRolloutBlock(label, promptModeCounts, sampleCount, renderedRate, injectedRate, averagePanelCount = null) {
    const metrics = [
        renderedRate === null ? null : `rendered ${formatRate(renderedRate)}`,
        injectedRate === null ? null : `injected ${formatRate(injectedRate)}`,
        averagePanelCount === null ? null : `avg panels ${formatDecimal(averagePanelCount)}`
    ].filter(Boolean);
    return `
        <div class="experiment-rollout-block">
            <div class="experiment-rollout-block__meta">
                <span class="task-badge">${escapeHtml(label)}</span>
                <span>${escapeHtml(String(sampleCount))} sampled</span>
            </div>
            <strong>${escapeHtml(summarizeFrequencyMap(promptModeCounts, "no prompt sample"))}</strong>
            <p>${escapeHtml(metrics.length > 0 ? metrics.join(" · ") : "no mounted-context telemetry")}</p>
        </div>
    `;
}

function renderExperimentPromptModeComparisonSection(
    promptModeSummaries,
    executionJudgmentPromptModeSummaries,
    completionJudgmentPromptModeSummaries,
    currentWorkerPromptMode,
    currentExecutionJudgmentPromptMode,
    currentCompletionJudgmentPromptMode
) {
    const hasWorkerPromptModes = orderedPromptModeKeys(promptModeSummaries).length > 0;
    const hasExecutionPromptModes = orderedPromptModeKeys(executionJudgmentPromptModeSummaries).length > 0;
    const hasCompletionPromptModes = orderedPromptModeKeys(completionJudgmentPromptModeSummaries).length > 0;
    if (!hasWorkerPromptModes && !hasExecutionPromptModes && !hasCompletionPromptModes) {
        return "";
    }
    return `
        <div class="experiment-prompt-section">
            <div class="stack-item__meta">
                <span class="task-badge">prompt rollout</span>
                <span>mounted-context prompt-mode comparison</span>
            </div>
            <div class="experiment-summary__grid">
                ${renderExperimentPromptModeComparisonCard("worker", promptModeSummaries, currentWorkerPromptMode)}
                ${renderExperimentPromptModeComparisonCard(
                    "exec judge",
                    executionJudgmentPromptModeSummaries,
                    currentExecutionJudgmentPromptMode
                )}
                ${renderExperimentPromptModeComparisonCard(
                    "done judge",
                    completionJudgmentPromptModeSummaries,
                    currentCompletionJudgmentPromptMode
                )}
            </div>
        </div>
    `;
}

function renderExperimentPromptModeComparisonCard(label, promptModeSummaries, currentPromptMode) {
    const promptModes = orderedPromptModeKeys(promptModeSummaries);
    const sampledCount = promptModes.reduce(
        (total, promptMode) => total + (numberOrNull(
            promptModeSummaries[promptMode]?.run_count,
            promptModeSummaries[promptMode]?.runCount
        ) ?? 0),
        0
    );
    return `
        <div class="experiment-mode-card experiment-prompt-card">
            <div class="stack-item__meta">
                <span class="task-badge">${escapeHtml(label)}</span>
                <span>${escapeHtml(String(sampledCount))} sampled</span>
                ${currentPromptMode
                    ? `<span>${escapeHtml(`current ${humanizeToken(currentPromptMode) || currentPromptMode}`)}</span>`
                    : ""}
            </div>
            <div class="experiment-prompt-list">
                ${promptModes.length > 0
                    ? promptModes
                        .map((promptMode) => renderExperimentPromptModeRow(
                            promptMode,
                            promptModeSummaries[promptMode],
                            currentPromptMode
                        ))
                        .join("")
                    : `<div class="experiment-rollout-block"><p>no prompt sample</p></div>`}
            </div>
        </div>
    `;
}

function renderExperimentPromptModeRow(promptMode, promptModeSummary, currentPromptMode) {
    const runCount = numberOrNull(promptModeSummary?.run_count, promptModeSummary?.runCount) ?? 0;
    const renderedRate = numberOrNull(
        promptModeSummary?.mounted_context_rendered_rate,
        promptModeSummary?.mountedContextRenderedRate
    );
    const renderUsedRate = numberOrNull(
        promptModeSummary?.mounted_render_used_rate,
        promptModeSummary?.mountedRenderUsedRate
    );
    const injectedRate = numberOrNull(
        promptModeSummary?.mounted_context_injected_rate,
        promptModeSummary?.mountedContextInjectedRate
    );
    const budgetTruncatedRate = numberOrNull(
        promptModeSummary?.mounted_context_budget_truncated_rate,
        promptModeSummary?.mountedContextBudgetTruncatedRate
    );
    const averagePanelCount = numberOrNull(
        promptModeSummary?.average_mounted_context_panel_count,
        promptModeSummary?.averageMountedContextPanelCount
    );
    const averageRenderedObjectCount = numberOrNull(
        promptModeSummary?.average_mounted_context_rendered_object_count,
        promptModeSummary?.averageMountedContextRenderedObjectCount
    );
    const headline = [
        renderedRate === null ? null : `rendered ${formatRate(renderedRate)}`,
        renderUsedRate === null ? null : `used ${formatRate(renderUsedRate)}`,
        injectedRate === null ? null : `injected ${formatRate(injectedRate)}`
    ].filter(Boolean);
    const detail = [
        budgetTruncatedRate === null ? null : `budget ${formatRate(budgetTruncatedRate)}`,
        averagePanelCount === null ? null : `avg panels ${formatDecimal(averagePanelCount)}`,
        averageRenderedObjectCount === null ? null : `avg objs ${formatDecimal(averageRenderedObjectCount)}`
    ].filter(Boolean);
    const isCurrent = promptMode === currentPromptMode ? " is-current" : "";
    return `
        <div class="experiment-rollout-block experiment-prompt-row${isCurrent}">
            <div class="experiment-rollout-block__meta">
                <span class="task-badge" data-tone="${promptMode === currentPromptMode ? "active" : "default"}">${escapeHtml(humanizeToken(promptMode) || promptMode)}</span>
                <span>${escapeHtml(String(runCount))} runs</span>
            </div>
            <strong>${escapeHtml(headline.length > 0 ? headline.join(" · ") : "no mounted-context telemetry")}</strong>
            <p>${escapeHtml(detail.length > 0 ? detail.join(" · ") : "no budget or object telemetry")}</p>
        </div>
    `;
}

function orderedPromptModeKeys(promptModeSummaries) {
    const preferredOrder = ["active_context_only", "mounted_context_shadow", "mounted_context_primary"];
    return Object.keys(promptModeSummaries || {})
        .filter((promptMode) => promptModeSummaries[promptMode] != null)
        .sort((left, right) => {
            const leftIndex = preferredOrder.indexOf(left);
            const rightIndex = preferredOrder.indexOf(right);
            const normalizedLeft = leftIndex === -1 ? Number.MAX_SAFE_INTEGER : leftIndex;
            const normalizedRight = rightIndex === -1 ? Number.MAX_SAFE_INTEGER : rightIndex;
            if (normalizedLeft !== normalizedRight) {
                return normalizedLeft - normalizedRight;
            }
            return left.localeCompare(right);
        });
}

function renderExperimentCaseCard(mode, caseComparison, currentMode) {
    const runsByMode = caseComparison?.runs_by_mode || caseComparison?.runsByMode || {};
    const run = runsByMode[mode];
    const isCurrent = mode === currentMode ? " is-current" : "";
    if (!run) {
        return `
            <div class="experiment-case-card${isCurrent}">
                <div class="stack-item__meta">
                    <span class="task-badge" data-tone="${mode === currentMode ? "active" : "default"}">${escapeHtml(mode)}</span>
                </div>
                <strong>missing</strong>
                <p>当前 case 还没有这个 mode 的 run。</p>
            </div>
        `;
    }
    const completionStatus = firstNonBlank(run.completion_status, run.completionStatus, "active");
    const acceptanceResult = firstNonBlank(run.acceptance_result, run.acceptanceResult, "not_evaluated");
    return `
        <div class="experiment-case-card${isCurrent}">
            <div class="stack-item__meta">
                <span class="task-badge" data-tone="${mode === currentMode ? "active" : "default"}">${escapeHtml(mode)}</span>
                <span>${escapeHtml(humanizeToken(completionStatus) || completionStatus)}</span>
            </div>
            <strong>${escapeHtml(humanizeToken(acceptanceResult) || acceptanceResult)}</strong>
            <p>${escapeHtml(`steps ${String(numberOrNull(run.total_steps, run.totalSteps) ?? 0)} · cost ${formatDecimal(numberOrNull(run.total_cost, run.totalCost), 2)}`)}</p>
        </div>
    `;
}

function renderCognitionTimeline(entries) {
    if (!Array.isArray(entries) || entries.length === 0) {
        return "";
    }
    return `
        <div class="cognition-timeline">
            ${entries.map((entry) => renderCognitionTimelineEntry(entry)).join("")}
        </div>
    `;
}

function renderCognitionTimelineEntry(entry) {
    const stage = firstNonBlank(entry?.stage, "unknown");
    const label = firstNonBlank(entry?.label, humanizeToken(stage) || stage);
    const summary = firstNonBlank(entry?.summary, summarizeCognitionTimelineEntry(entry), "no summary");
    const chips = cognitionTimelineChips(entry);
    return `
        <div class="cognition-timeline__entry">
            <div class="cognition-timeline__meta">
                <span class="task-badge">${escapeHtml(label)}</span>
                ${entry?.occurred_at || entry?.occurredAt ? `<span>${escapeHtml(formatTime(entry.occurred_at || entry.occurredAt))}</span>` : ""}
            </div>
            <strong>${escapeHtml(preview(summary, 220))}</strong>
            ${chips.length > 0 ? `
                <div class="chip-list experiment-summary__chips">
                    ${chips.map((chip) => `<span class="chip">${escapeHtml(chip)}</span>`).join("")}
                </div>
            ` : ""}
        </div>
    `;
}

function cognitionTimelineChips(entry) {
    const workerId = firstNonBlank(entry?.worker_id, entry?.workerId);
    const continuityAction = firstNonBlank(entry?.continuity_action, entry?.continuityAction);
    const checkpointType = firstNonBlank(entry?.checkpoint_type, entry?.checkpointType);
    const reason = firstNonBlank(entry?.reason);
    const targetWorker = firstNonBlank(entry?.target_worker, entry?.targetWorker);
    const promptMode = firstNonBlank(entry?.prompt_mode, entry?.promptMode);
    const routeSource = firstNonBlank(entry?.route_source, entry?.routeSource);
    const executionStatus = firstNonBlank(entry?.execution_status, entry?.executionStatus);
    const toolCount = numberOrNull(entry?.tool_invocation_count, entry?.toolInvocationCount);
    const mountedRendered = booleanValue(entry?.mounted_context_rendered, entry?.mountedContextRendered);
    const mountedInjected = booleanValue(entry?.mounted_context_injected, entry?.mountedContextInjected);
    const mountedPanelCount = numberOrNull(entry?.mounted_context_panel_count, entry?.mountedContextPanelCount);
    const renderedObjectCount = numberOrNull(
        entry?.mounted_context_rendered_object_count,
        entry?.mountedContextRenderedObjectCount
    );
    const hiddenObjectCount = numberOrNull(
        entry?.mounted_context_hidden_object_count,
        entry?.mountedContextHiddenObjectCount
    );
    const renderedSelectionTraceCount = numberOrNull(
        entry?.mounted_context_rendered_selection_trace_count,
        entry?.mountedContextRenderedSelectionTraceCount
    );
    const hiddenSelectionTraceCount = numberOrNull(
        entry?.mounted_context_hidden_selection_trace_count,
        entry?.mountedContextHiddenSelectionTraceCount
    );
    const budgetTruncated = booleanValue(
        entry?.mounted_context_budget_truncated,
        entry?.mountedContextBudgetTruncated
    );
    const aligned = booleanValue(entry?.aligned_with_previous_prompt_mode, entry?.alignedWithPreviousPromptMode);
    const needsContextReopen = booleanValue(entry?.needs_context_reopen, entry?.needsContextReopen);
    const evidenceGapDetected = booleanValue(entry?.evidence_gap_detected, entry?.evidenceGapDetected);
    const needsArchiveRetrieval = booleanValue(entry?.needs_archive_retrieval, entry?.needsArchiveRetrieval);
    const needsExternalFactRefresh = booleanValue(entry?.needs_external_fact_refresh, entry?.needsExternalFactRefresh);
    const reopenSummary = firstNonBlank(entry?.reopen_summary, entry?.reopenSummary);
    const reopenCandidatePaths = normalizeTextList(entry?.reopen_candidate_paths, entry?.reopenCandidatePaths);
    const evidenceRefs = normalizeTextList(entry?.evidence_refs, entry?.evidenceRefs);
    const unfinishedItems = normalizeTextList(entry?.unfinished_items, entry?.unfinishedItems);
    return [
        workerId ? `worker: ${workerId}` : null,
        continuityAction ? `action: ${humanizeToken(continuityAction) || continuityAction}` : null,
        checkpointType ? `checkpoint: ${humanizeToken(checkpointType) || checkpointType}` : null,
        targetWorker ? `target: ${targetWorker}` : null,
        routeSource ? `route: ${humanizeToken(routeSource) || routeSource}` : null,
        promptMode ? `prompt: ${humanizeToken(promptMode) || promptMode}` : null,
        executionStatus ? `status: ${humanizeToken(executionStatus) || executionStatus}` : null,
        needsArchiveRetrieval === true ? "archive retrieval requested" : null,
        needsExternalFactRefresh === true ? "external fact refresh requested" : null,
        needsContextReopen === true ? "context reopen requested" : null,
        evidenceGapDetected === true ? "evidence gap detected" : null,
        reopenCandidatePaths.length > 0 ? `${reopenCandidatePaths.length} reopen targets` : null,
        reopenSummary ? `reopen: ${preview(reopenSummary, 72)}` : null,
        toolCount === null ? null : `${toolCount} tools`,
        mountedRendered === true ? "mounted rendered" : null,
        mountedInjected === true ? "mounted injected" : null,
        mountedPanelCount === null ? null : `${mountedPanelCount} panels`,
        renderedObjectCount !== null || hiddenObjectCount !== null
            ? `${renderedObjectCount ?? 0}/${hiddenObjectCount ?? 0} objects`
            : null,
        renderedSelectionTraceCount !== null || hiddenSelectionTraceCount !== null
            ? `${renderedSelectionTraceCount ?? 0}/${hiddenSelectionTraceCount ?? 0} traces`
            : null,
        budgetTruncated === true ? "budget truncated" : null,
        aligned === true ? "prompt aligned" : null,
        aligned === false ? "prompt diverged" : null,
        reason ? `reason: ${preview(reason, 48)}` : null,
        evidenceRefs.length > 0 ? `${evidenceRefs.length} evidence` : null,
        unfinishedItems.length > 0 ? `${unfinishedItems.length} unfinished` : null
    ].filter(Boolean);
}

function summarizeCognitionTimelineEntry(entry) {
    return [
        firstNonBlank(entry?.continuity_action, entry?.continuityAction),
        firstNonBlank(entry?.checkpoint_type, entry?.checkpointType),
        firstNonBlank(entry?.worker_id, entry?.workerId),
        firstNonBlank(entry?.target_worker, entry?.targetWorker),
        firstNonBlank(entry?.prompt_mode, entry?.promptMode),
        firstNonBlank(entry?.execution_status, entry?.executionStatus),
        firstNonBlank(entry?.route_source, entry?.routeSource),
        booleanValue(entry?.needs_archive_retrieval, entry?.needsArchiveRetrieval) === true
            ? "archive retrieval requested"
            : null,
        booleanValue(entry?.needs_external_fact_refresh, entry?.needsExternalFactRefresh) === true
            ? "external fact refresh requested"
            : null,
        booleanValue(entry?.needs_context_reopen, entry?.needsContextReopen) === true
            ? "context reopen requested"
            : null,
        firstNonBlank(entry?.reopen_summary, entry?.reopenSummary),
        firstNonBlank(entry?.reason)
    ].filter(Boolean).join(" · ");
}

function routeSignal(flow) {
    const routePreview = flow?.route_preview || flow?.routePreview || {};
    const routeSurface = flow?.runtime_cognition_surface?.route || flow?.runtimeCognitionSurface?.route || {};
    const metadata = experimentRunMetadata(flow);
    const worker = firstNonBlank(
        routeSurface.selected_worker,
        routeSurface.selectedWorker,
        routePreview.selected_worker,
        routePreview.selectedWorker,
        metadata.assigned_worker,
        metadata.assignedWorker
    );
    const source = firstNonBlank(
        routeSurface.route_source,
        routeSurface.routeSource,
        routePreview.route_source,
        routePreview.routeSource,
        metadata.route_source,
        metadata.routeSource
    );
    if (!worker) {
        return null;
    }
    return source ? `${worker} via ${humanizeToken(source) || source}` : worker;
}

function toolChainFacts(flow, tools = []) {
    const metadata = experimentRunMetadata(flow);
    const stepCount = numericValue(
        metadata.tool_chain_step_count,
        metadata.toolChainStepCount
    ) ?? maxToolStepIndex(tools);
    const terminationReason = firstNonBlank(
        metadata.tool_chain_termination_reason,
        metadata.toolChainTerminationReason
    );
    const traceSummary = firstNonBlank(
        metadata.tool_chain_trace_summary,
        metadata.toolChainTraceSummary
    );
    const toolExecutionMode = firstNonBlank(
        metadata.tool_execution_mode,
        metadata.toolExecutionMode
    );
    const toolNames = normalizeTextList(
        metadata.tool_chain_tools,
        metadata.toolChainTools,
        tools.map((tool) => tool.tool_name || tool.toolName)
    );
    return {
        stepCount,
        terminationReason,
        traceSummary,
        toolExecutionMode,
        toolNames
    };
}

function toolChainLabel(flow, tools = []) {
    const facts = toolChainFacts(flow, tools);
    const parts = [
        facts.stepCount ? formatCount(facts.stepCount, "step") : null,
        facts.terminationReason ? humanizeToken(facts.terminationReason) : null,
        !facts.terminationReason && facts.toolExecutionMode ? humanizeToken(facts.toolExecutionMode) : null
    ].filter(Boolean);
    return parts.length > 0 ? parts.join(" · ") : null;
}

function toolChainNarrative(flow, tools = []) {
    const facts = toolChainFacts(flow, tools);
    if (facts.traceSummary) {
        return facts.traceSummary.replace(/_/g, " ");
    }
    const label = toolChainLabel(flow, tools);
    if (!label && facts.toolNames.length === 0) {
        return null;
    }
    if (facts.toolNames.length === 0) {
        return label;
    }
    return [label, facts.toolNames.map(humanizeToken).join(" -> ")].filter(Boolean).join(" · ");
}

function summarizeExecutionSurface(surface) {
    if (!surface || Object.keys(surface).length === 0) {
        return null;
    }
    const worker = firstNonBlank(surface.worker_id, surface.workerId);
    const status = firstNonBlank(surface.execution_status, surface.executionStatus);
    const promptMode = firstNonBlank(surface.prompt_mode, surface.promptMode);
    const mountedRendered = booleanValue(surface.mounted_context_rendered, surface.mountedContextRendered);
    const mountedRenderUsed = booleanValue(surface.mounted_render_used, surface.mountedRenderUsed);
    const mountedInjected = booleanValue(surface.mounted_context_injected, surface.mountedContextInjected);
    const panelCount = numericValue(surface.mounted_context_panel_count, surface.mountedContextPanelCount);
    const nonEmptyPanelCount = numericValue(
        surface.mounted_context_non_empty_panel_count,
        surface.mountedContextNonEmptyPanelCount
    );
    const activeCount = numericValue(surface.mounted_active_count, surface.mountedActiveCount);
    const evidenceCount = numericValue(surface.mounted_evidence_count, surface.mountedEvidenceCount);
    const archiveCount = numericValue(surface.mounted_archive_count, surface.mountedArchiveCount);
    const parts = [
        worker ? `worker ${worker}` : null,
        status ? humanizeToken(status) || status : null,
        promptMode ? `prompt ${humanizeToken(promptMode) || promptMode}` : null,
        mountedRendered === true ? "mounted rendered" : null,
        mountedRendered === false ? "mounted not rendered" : null,
        mountedRenderUsed === true ? "mounted used" : null,
        mountedRenderUsed === false ? "mounted unused" : null,
        mountedInjected === true ? "mounted injected" : null,
        mountedInjected === false ? "mounted not injected" : null,
        panelCount ? `${panelCount} panels` : null,
        nonEmptyPanelCount ? `${nonEmptyPanelCount} non-empty` : null,
        activeCount ? `${activeCount} active` : null,
        evidenceCount ? `${evidenceCount} evidence` : null,
        archiveCount ? `${archiveCount} archive` : null
    ].filter(Boolean);
    if (parts.length === 0) {
        return null;
    }
    return { label: "execution", value: parts.join(" · ") };
}

function summarizeProviderRunFiles(surface) {
    if (!surface || Object.keys(surface).length === 0) {
        return null;
    }
    const paths = [
        ["run", firstNonBlank(surface.provider_run_dir, surface.providerRunDir)],
        ["last", firstNonBlank(surface.provider_last_message_path, surface.providerLastMessagePath)],
        ["events", firstNonBlank(surface.provider_event_log_path, surface.providerEventLogPath)],
        ["stdout", firstNonBlank(surface.provider_stdout_path, surface.providerStdoutPath)],
        ["meta", firstNonBlank(surface.provider_run_metadata_path, surface.providerRunMetadataPath)]
    ]
        .filter(([, value]) => value)
        .map(([label, value]) => `${label}: ${preview(value, 140)}`);
    if (paths.length === 0) {
        return null;
    }
    return { label: "run files", value: paths.join(" · ") };
}

function summarizeJudgmentSurface(label, surface) {
    if (!surface || Object.keys(surface).length === 0) {
        return null;
    }
    const promptMode = firstNonBlank(surface.prompt_mode, surface.promptMode);
    const mountedRendered = booleanValue(surface.mounted_context_rendered, surface.mountedContextRendered);
    const mountedRenderUsed = booleanValue(surface.mounted_render_used, surface.mountedRenderUsed);
    const mountedInjected = booleanValue(surface.mounted_context_injected, surface.mountedContextInjected);
    const panelCount = numericValue(surface.mounted_context_panel_count, surface.mountedContextPanelCount);
    const nonEmptyPanelCount = numericValue(
        surface.mounted_context_non_empty_panel_count,
        surface.mountedContextNonEmptyPanelCount
    );
    const activeCount = numericValue(surface.mounted_active_count, surface.mountedActiveCount);
    const evidenceCount = numericValue(surface.mounted_evidence_count, surface.mountedEvidenceCount);
    const archiveCount = numericValue(surface.mounted_archive_count, surface.mountedArchiveCount);
    const evidenceRefs = normalizeTextList(surface.evidence_refs, surface.evidenceRefs);
    const unfinishedItems = normalizeTextList(surface.unfinished_items, surface.unfinishedItems);
    const parts = [
        promptMode ? `prompt ${humanizeToken(promptMode) || promptMode}` : null,
        mountedRendered === true ? "mounted rendered" : null,
        mountedRendered === false ? "mounted not rendered" : null,
        mountedRenderUsed === true ? "mounted used" : null,
        mountedRenderUsed === false ? "mounted unused" : null,
        mountedInjected === true ? "mounted injected" : null,
        mountedInjected === false ? "mounted not injected" : null,
        panelCount ? `${panelCount} panels` : null,
        nonEmptyPanelCount ? `${nonEmptyPanelCount} non-empty` : null,
        activeCount ? `${activeCount} active` : null,
        evidenceRefs.length > 0 ? `${evidenceRefs.length} evidence` : null,
        evidenceCount ? `${evidenceCount} evidence budget` : null,
        archiveCount ? `${archiveCount} archive` : null,
        unfinishedItems.length > 0 ? `${unfinishedItems.length} unfinished` : null
    ].filter(Boolean);
    if (parts.length === 0) {
        return null;
    }
    return { label, value: parts.join(" · ") };
}

function summarizeAlignmentSurface(surface) {
    if (!surface || Object.keys(surface).length === 0) {
        return [];
    }
    return [
        alignmentChip(
            "route/execution",
            booleanValue(surface.route_worker_matches_execution_worker, surface.routeWorkerMatchesExecutionWorker)
        ),
        alignmentChip(
            "exec/judge prompt",
            booleanValue(
                surface.execution_and_execution_judgment_prompt_mode_aligned,
                surface.executionAndExecutionJudgmentPromptModeAligned
            )
        ),
        alignmentChip(
            "exec/done prompt",
            booleanValue(
                surface.execution_and_completion_judgment_prompt_mode_aligned,
                surface.executionAndCompletionJudgmentPromptModeAligned
            )
        )
    ].filter(Boolean);
}

function alignmentChip(label, aligned) {
    if (aligned === true) {
        return `${label}: aligned`;
    }
    if (aligned === false) {
        return `${label}: diverged`;
    }
    return null;
}

function renderToolChainSummaryCard(facts, label, summary) {
    return stackItem(
        "tool chain",
        label || "summary",
        preview(summary, 220),
        facts.toolNames.length > 0 ? facts.toolNames.map(humanizeToken).join(", ") : ""
    );
}

function numberOrNull(...values) {
    for (const value of values) {
        const number = Number(value);
        if (Number.isFinite(number)) {
            return number;
        }
    }
    return null;
}

function booleanValue(...values) {
    for (const value of values) {
        if (value === true || value === "true") {
            return true;
        }
        if (value === false || value === "false") {
            return false;
        }
    }
    return null;
}

function numericValue(...values) {
    for (const value of values) {
        const number = Number(value);
        if (Number.isFinite(number) && number > 0) {
            return number;
        }
    }
    return null;
}

function maxToolStepIndex(tools = []) {
    let max = 0;
    tools.forEach((tool) => {
        const step = numericValue(tool.metadata?.step_index, tool.metadata?.stepIndex);
        if (step && step > max) {
            max = step;
        }
    });
    return max > 0 ? max : null;
}

function normalizeTextList(...values) {
    for (const value of values) {
        if (Array.isArray(value)) {
            const items = value
                .map((item) => typeof item === "string" ? item.trim() : "")
                .filter(Boolean);
            if (items.length > 0) {
                return [...new Set(items)];
            }
        }
    }
    return [];
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

function formatRate(value) {
    const number = numberOrNull(value);
    if (number === null) {
        return "0%";
    }
    const percent = number * 100;
    return Number.isInteger(percent) ? `${percent.toFixed(0)}%` : `${percent.toFixed(1)}%`;
}

function formatDecimal(value, digits = 1) {
    const number = numberOrNull(value);
    if (number === null) {
        return digits === 0 ? "0" : (0).toFixed(digits);
    }
    return number.toFixed(digits);
}

function summarizeCountMap(map) {
    const entries = Object.entries(map || {})
        .filter(([, count]) => numberOrNull(count) !== null)
        .sort((left, right) => {
            const countDiff = Number(right[1]) - Number(left[1]);
            if (countDiff !== 0) {
                return countDiff;
            }
            return String(left[0]).localeCompare(String(right[0]));
        });
    if (entries.length === 0) {
        return "no route sample";
    }
    return entries.slice(0, 3)
        .map(([key, count]) => `${humanizeToken(key) || key} ${count}`)
        .join(" · ");
}

function summarizeFrequencyMap(map, emptyLabel = "no sample") {
    const entries = Object.entries(map || {})
        .filter(([, count]) => {
            const number = numberOrNull(count);
            return number !== null && number > 0;
        })
        .sort((left, right) => {
            const countDiff = Number(right[1]) - Number(left[1]);
            if (countDiff !== 0) {
                return countDiff;
            }
            return String(left[0]).localeCompare(String(right[0]));
        });
    if (entries.length === 0) {
        return emptyLabel;
    }
    return entries.slice(0, 3)
        .map(([key, count]) => `${humanizeToken(key) || key} ${count}`)
        .join(" · ");
}

function formatCount(value, noun) {
    return `${value} ${noun}${value === 1 ? "" : "s"}`;
}

function valueLine(label, value) {
    const text = firstNonBlank(value);
    return text ? `${label}: ${preview(text, 96)}` : null;
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
    }
    return null;
}

function applyLocationSelection() {
    const params = new URLSearchParams(window.location.hash.replace(/^#/, ""));
    const sessionId = firstNonBlank(params.get("session"), params.get("session_id"));
    const taskId = firstNonBlank(params.get("task"), params.get("task_id"));
    const rail = firstNonBlank(params.get("rail"), params.get("sidebar"));
    const details = firstNonBlank(params.get("details"), params.get("panel"));
    if (sessionId) {
        state.selectedSessionId = sessionId;
    }
    if (taskId) {
        state.selectedTaskId = taskId;
    }
    if (rail === "open") {
        state.sidebarOpen = true;
    } else if (rail === "closed") {
        state.sidebarOpen = false;
    }
    if (details === "open") {
        state.detailsOpen = true;
    } else if (details === "closed") {
        state.detailsOpen = false;
    } else if (details !== null) {
        state.detailsOpen = true;
    }
    state.facadeSurface = readFacadeSurfaceFromHash(window.location.hash, { firstNonBlank });
}

function currentHashTaskId() {
    const params = new URLSearchParams(window.location.hash.replace(/^#/, ""));
    return firstNonBlank(params.get("task"), params.get("task_id")) || "";
}

function syncLocationSelection() {
    const params = new URLSearchParams();
    if (state.selectedSessionId) {
        params.set("session", state.selectedSessionId);
    }
    if (state.selectedTaskId) {
        params.set("task", state.selectedTaskId);
    }
    if (window.innerWidth > 1140 && !state.sidebarOpen) {
        params.set("rail", "closed");
    }
    if (state.detailsOpen) {
        params.set("details", "open");
    } else {
        params.set("details", "closed");
    }
    writeFacadeSurfaceToParams(state.facadeSurface, params);
    const nextHash = params.toString() ? `#${params.toString()}` : "";
    if (window.location.hash === nextHash) {
        return;
    }
    window.history.replaceState(null, "", `${window.location.pathname}${window.location.search}${nextHash}`);
}

function renderWorkspaceSurface() {
    if (dom.workspaceSurfaceTitle) {
        dom.workspaceSurfaceTitle.textContent = "Session Transcript";
    }
}

function renderThreadDrawer() {
    if (dom.threadDrawer) {
        dom.threadDrawer.hidden = !state.selectedSessionId;
    }
    if (dom.threadDrawerMeta) {
        if (!state.selectedSessionId) {
            dom.threadDrawerMeta.textContent = "先选一个 thread。";
        } else if (state.tasks.length === 0) {
            dom.threadDrawerMeta.textContent = "当前 thread 还没有 task。";
        } else {
            const activeCount = state.tasks.filter((task) => (task.status || "active").toLowerCase() === "active").length;
            dom.threadDrawerMeta.textContent = activeCount > 0
                ? `${state.tasks.length} 轮任务 · ${activeCount} 轮仍在推进`
                : `${state.tasks.length} 轮任务 · 当前没有 active task`;
        }
    }
}

function toggleSidebar() {
    setSidebarOpen(!state.sidebarOpen);
}

function setSidebarOpen(open) {
    state.sidebarOpen = open;
    renderSidebarState();
    syncLocationSelection();
}

function toggleDetailsPanel() {
    setDetailsOpen(!state.detailsOpen);
}

function setDetailsOpen(open) {
    state.detailsOpen = open;
    renderDetailsPanelState();
    syncLocationSelection();
}

function renderSidebarState() {
    const open = state.sidebarOpen;
    document.body.classList.toggle("sidebar-open", open);
    document.body.classList.toggle("sidebar-collapsed", !open && window.innerWidth > 1140);
    if (dom.sessionSidebar) {
        dom.sessionSidebar.classList.toggle("is-open", open);
    }
    if (dom.sidebarToggle) {
        dom.sidebarToggle.setAttribute("aria-expanded", String(open));
        dom.sidebarToggle.classList.toggle("is-active", open);
    }
    if (dom.sidebarBackdrop) {
        dom.sidebarBackdrop.hidden = !(open && window.innerWidth <= 1140);
    }
}

function renderDetailsPanelState() {
    const open = state.detailsOpen;
    document.body.classList.toggle("details-collapsed", !open);
    if (dom.taskDetailsPanel) {
        dom.taskDetailsPanel.setAttribute("aria-hidden", String(!open));
    }
    if (dom.detailsToggleButton) {
        dom.detailsToggleButton.textContent = open ? "收起细节" : "查看细节";
        dom.detailsToggleButton.classList.toggle("is-active", open);
    }
}

function syncSidebarForViewport() {
    if (window.innerWidth <= 1140 && !window.location.hash.includes("rail=open")) {
        state.sidebarOpen = false;
    } else if (window.innerWidth > 1140 && !window.location.hash.includes("rail=closed")) {
        state.sidebarOpen = true;
    }
    renderSidebarState();
}

function onViewportResize() {
    syncSidebarForViewport();
    renderDetailsPanelState();
}

function buildTaskChains(tasks) {
    const tasksById = mapById(tasks);
    const chains = new Map();
    tasks.forEach((task) => {
        const rootId = resolveRootTaskId(task, tasksById);
        const existing = chains.get(rootId) || {
            rootId,
            rootTask: tasksById.get(rootId) || task,
            latestTask: task,
            tasks: []
        };
        existing.tasks.push(task);
        chains.set(rootId, existing);
    });

    return Array.from(chains.values())
        .map((chain) => {
            chain.tasks.sort((left, right) => createdAtMillis(left) - createdAtMillis(right));
            chain.rootTask = tasksById.get(chain.rootId) || chain.tasks[0];
            chain.latestTask = chain.tasks[chain.tasks.length - 1];
            return chain;
        })
        .sort((left, right) => createdAtMillis(left.latestTask) - createdAtMillis(right.latestTask));
}

function findTaskChain(taskId) {
    return buildTaskChains(state.tasks).find((chain) => chain.tasks.some((task) => task.id === taskId)) || null;
}

function renderChainContext(task) {
    const chain = findTaskChain(task.id);
    if (!chain) {
        return emptyState("当前任务还没有迭代链上下文。");
    }

    const currentIndex = chain.tasks.findIndex((item) => item.id === task.id);
    if (currentIndex < 0) {
        return emptyState("当前任务还没有迭代链上下文。");
    }

    const chainPlan = buildChainContextPlan(chain.tasks, task.id);
    const previousTask = chainPlan.previousTask;
    const nextTask = chainPlan.nextTask;
    const currentRound = chainPlan.currentIndex <= 0 ? "root" : `round ${chainPlan.currentIndex + 1}`;
    const latestTask = chain.latestTask || chain.tasks[chain.tasks.length - 1];

    return `
        <div class="chain-context">
            <div class="chain-context__meta">
                <div>
                    <p class="eyebrow">Chain Snapshot</p>
                    <strong>${escapeHtml(chain.rootTask?.title || chain.rootId)}</strong>
                </div>
                <div class="dialogue-chain__meta">
                    <span class="task-badge">${escapeHtml(`${chain.tasks.length} tasks`)}</span>
                    <span class="task-badge">${escapeHtml(currentRound)}</span>
                    <span class="task-badge" data-tone="${toneForStatus(latestTask?.status)}">${escapeHtml(latestTask?.status || "active")}</span>
                </div>
            </div>
            <div class="chain-context__nav">
                <button class="button button--ghost" type="button" ${previousTask ? `data-chain-task-id="${escapeHtml(previousTask.id)}"` : "disabled"}>
                    ${previousTask ? `上一轮 · ${escapeHtml(previousTask.title || previousTask.id)}` : "已经是首轮"}
                </button>
                <button class="button button--ghost" type="button" ${nextTask ? `data-chain-task-id="${escapeHtml(nextTask.id)}"` : "disabled"}>
                    ${nextTask ? `下一轮 · ${escapeHtml(nextTask.title || nextTask.id)}` : "已经是最新一轮"}
                </button>
            </div>
            ${renderChainContextListHtml(chainPlan, {
                escapeHtml,
                renderTask: (item) => renderChainContextTask(item, chain.tasks, task.id)
            })}
        </div>
    `;
}

function renderChainContextTask(item, allTasks, selectedTaskId) {
    const index = allTasks.findIndex((task) => task.id === item.id);
    const active = item.id === selectedTaskId ? "is-active" : "";
    const roundLabel = index === 0 ? "root" : `round ${index + 1}`;
    return `
        <button class="chain-context__task ${active}" type="button" data-chain-task-id="${escapeHtml(item.id)}">
            <div class="chain-context__task-head">
                <span class="task-badge">${escapeHtml(roundLabel)}</span>
                <span class="task-badge" data-tone="${toneForStatus(item.status)}">${escapeHtml(item.status || "active")}</span>
                ${renderStartModeBadge(item)}
            </div>
            <strong class="chain-context__task-title">${escapeHtml(item.title || item.id)}</strong>
            <div class="chain-context__task-meta">
                <span>${formatTime(item.created_at || item.createdAt)}</span>
                <span>${escapeHtml(item.control_node || item.controlNode || "intake")}</span>
                <span class="mono">${escapeHtml(item.id)}</span>
            </div>
        </button>
    `;
}

function renderMountedContext(view) {
    if (!view || typeof view !== "object") {
        return emptyState("当前任务没有 mounted context。");
    }
    const panels = Array.isArray(view.panels) ? view.panels.filter(Boolean) : [];
    const selectionTrace = normalizeTextList(view.selection_trace, view.selectionTrace);
    const nonEmptyPanels = panels.filter((panel) => Array.isArray(panel?.objects) && panel.objects.filter(Boolean).length > 0);
    if (nonEmptyPanels.length === 0 && selectionTrace.length === 0) {
        return emptyState("当前 mounted context 为空。");
    }
    const traceHtml = selectionTrace.length > 0
        ? `<div class="chip-list mounted-context__trace">${selectionTrace.slice(0, 8).map((item) => `<span class="chip">${escapeHtml(preview(item, 120))}</span>`).join("")}</div>`
        : "";
    return `
        <div class="mounted-context">
            <div class="stack-item__meta mounted-context__meta">
                <span class="task-badge">${escapeHtml(`${nonEmptyPanels.length} panels`)}</span>
                ${view.task_id || view.taskId ? `<span class="task-badge mono">${escapeHtml(view.task_id || view.taskId)}</span>` : ""}
            </div>
            ${traceHtml}
            <div class="stack-list mounted-context__panels">
                ${nonEmptyPanels.map(renderMountedPanel).join("")}
            </div>
        </div>
    `;
}

function renderMountedPanel(panel) {
    const name = firstNonBlank(panel?.title, humanizeToken(panel?.name), panel?.name, "panel");
    const objects = Array.isArray(panel?.objects) ? panel.objects.filter(Boolean) : [];
    return `
        <div class="stack-item mounted-context__panel">
            <div class="stack-item__meta">
                <span class="task-badge">${escapeHtml(name)}</span>
                <span class="task-badge">${escapeHtml(String(objects.length))}</span>
            </div>
            <strong>${escapeHtml(name)}</strong>
            <div class="stack-list mounted-context__objects">
                ${objects.slice(0, 6).map(renderMountedObjectCard).join("")}
                ${objects.length > 6 ? emptyState(`还有 ${objects.length - 6} 个对象未展开。`) : ""}
            </div>
        </div>
    `;
}

function renderMountedObjectCard(object) {
    const title = firstNonBlank(object?.title, object?.path, object?.id, "object");
    const summary = firstNonBlank(object?.summary, object?.content_preview, object?.contentPreview, "");
    const type = firstNonBlank(object?.type, "object");
    const retention = firstNonBlank(object?.retention_state, object?.retentionState);
    const metadata = object?.metadata && typeof object.metadata === "object" ? object.metadata : {};
    const refs = Array.isArray(object?.refs) ? object.refs.filter(Boolean) : [];
    const candidatePaths = normalizeTextList(
        metadata.retrieval_candidate_paths,
        metadata.reopen_candidate_paths,
        metadata.retrievalCandidatePaths,
        metadata.reopenCandidatePaths
    );
    const nextFollowups = normalizeTextList(metadata.next_followups, metadata.nextFollowups);
    const chips = [
        retention ? `retention: ${retention}` : null,
        isTrueFlag(metadata.rehydrated_from_archive) ? "rehydrated" : null,
        isTrueFlag(metadata.needs_archive_retrieval) ? "archive retrieval" : null,
        isTrueFlag(metadata.needs_external_fact_refresh) ? "external refresh" : null,
        isTrueFlag(metadata.needs_context_reopen) ? "context reopen" : null,
        refs.length > 0 ? `refs: ${refs.length}` : null
    ].filter(Boolean);
    const detailLines = [
        summary,
        candidatePaths.length > 0 ? `targets: ${candidatePaths.slice(0, 3).map((item) => preview(item, 44)).join(" · ")}` : null,
        nextFollowups.length > 0 ? `next: ${nextFollowups.slice(0, 2).map((item) => preview(item, 60)).join(" · ")}` : null
    ].filter(Boolean);
    return stackItem(
        humanizeToken(type) || type,
        preview(title, 96),
        [detailLines.join("\n"), chips.length > 0 ? chips.join(" · ") : null].filter(Boolean).join("\n"),
        retention ? humanizeToken(retention) || retention : ""
    );
}

function resolveRootTaskId(task, tasksById) {
    let current = task;
    const visited = new Set([task.id]);
    while (current) {
        const parentId = taskParentId(current);
        if (!parentId || visited.has(parentId)) {
            return current.id;
        }
        const parent = tasksById.get(parentId);
        if (!parent) {
            return current.id;
        }
        current = parent;
        visited.add(current.id);
    }
    return task.id;
}

function taskParent(task, tasksById) {
    const parentId = taskParentId(task);
    return parentId ? tasksById.get(parentId) || null : null;
}

function taskParentId(task) {
    return firstNonBlank(
        task?.parent_task_id,
        task?.parentTaskId,
        task?.metadata?.parent_task_id,
        task?.metadata?.parentTaskId,
        task?.metadata?.followup_parent_task_id
    );
}

function taskStartMode(task) {
    const explicitMode = firstNonBlank(task?.metadata?.start_mode, task?.metadata?.startMode);
    if (explicitMode === "manual") {
        return "manual-start";
    }
    if (explicitMode === "auto") {
        return "auto-start";
    }
    const autoStart = task?.metadata?.auto_start ?? task?.metadata?.autoStart;
    if (autoStart === false || autoStart === "false") {
        return "manual-start";
    }
    if (autoStart === true || autoStart === "true") {
        return "auto-start";
    }
    return null;
}

function renderStartModeBadge(task) {
    const mode = taskStartMode(task);
    if (!mode) {
        return "";
    }
    return `<span class="task-badge" data-tone="${startModeTone(mode)}">${escapeHtml(mode)}</span>`;
}

function startModeTone(mode) {
    return mode === "manual-start" ? "manual" : "auto";
}

function mapById(items) {
    return new Map((items || []).map((item) => [item.id, item]));
}

function createdAtMillis(task) {
    const date = new Date(normalizeTimestampValue(task?.created_at || task?.createdAt || 0));
    return Number.isNaN(date.getTime()) ? 0 : date.getTime();
}

function toChipLines(prefix, lines) {
    return (lines || []).slice(0, 8).map((line) => `${prefix}: ${preview(line, 90)}`);
}

function emptyState(text) {
    return `<div class="empty-state">${escapeHtml(text)}</div>`;
}

function deriveTitle(intent) {
    const compact = intent.replace(/\s+/g, " ").trim();
    return compact.length <= 28 ? compact : `${compact.slice(0, 28)}...`;
}

function preview(value, maxLength) {
    const text = String(value || "").replace(/\s+/g, " ").trim();
    if (text.length <= maxLength) {
        return text;
    }
    return `${text.slice(0, maxLength)}...`;
}

function toneForStatus(status) {
    switch ((status || "").toLowerCase()) {
        case "active":
            return "active";
        case "paused":
        case "waiting":
            return "paused";
        case "done":
            return "done";
        case "failed":
            return "failed";
        default:
            return "default";
    }
}

function formatTime(value) {
    if (!value) {
        return "unknown";
    }
    const normalized = normalizeTimestampValue(value);
    const date = new Date(normalized);
    if (Number.isNaN(date.getTime())) {
        return String(value);
    }
    return date.toLocaleString("zh-CN", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false
    });
}

function normalizeTimestampValue(value) {
    if (value === null || value === undefined || value === "") {
        return value;
    }
    if (typeof value === "number") {
        return normalizeEpochNumber(value);
    }
    if (typeof value === "string") {
        const trimmed = value.trim();
        if (!trimmed) {
            return value;
        }
        const numeric = Number(trimmed);
        if (Number.isFinite(numeric) && /^-?\d+(?:\.\d+)?$/.test(trimmed)) {
            return normalizeEpochNumber(numeric);
        }
        return trimmed;
    }
    return value;
}

function normalizeEpochNumber(value) {
    if (!Number.isFinite(value)) {
        return value;
    }
    return Math.abs(value) < 1e12 ? value * 1000 : value;
}

function timestampMs(value) {
    const normalized = normalizeTimestampValue(value);
    const date = new Date(normalized);
    return Number.isNaN(date.getTime()) ? 0 : date.getTime();
}

function showToast(message, isError = false) {
    dom.toast.textContent = message;
    dom.toast.style.background = isError ? "rgba(116, 53, 46, 0.95)" : "rgba(22, 33, 45, 0.93)";
    dom.toast.classList.add("is-visible");
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(() => {
        dom.toast.classList.remove("is-visible");
    }, 2400);
}

function setTaskActionState(enabled) {
    dom.taskActions.querySelectorAll("button").forEach((button) => {
        button.disabled = !enabled;
    });
    dom.taskSecondaryActions.querySelectorAll("button").forEach((button) => {
        button.disabled = !enabled;
    });
    dom.handoffButton.disabled = !enabled || state.workers.length === 0;
    dom.refreshThreadButton.disabled = false;
}

function setButtonBusy(button, busy, idleLabel, busyLabel) {
    if (!button) {
        return;
    }
    button.disabled = busy;
    button.textContent = busy ? busyLabel : idleLabel;
}

function isClosedSession(session) {
    return (session?.status || "").toLowerCase() === "closed";
}

function handleError(error) {
    console.error(error);
    showToast(error.message || "请求失败", true);
}

function escapeHtml(value) {
    return String(value || "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

async function api(path, options = {}) {
    const response = await fetch(path, {
        headers: {
            "Content-Type": "application/json"
        },
        ...options
    });

    const payload = await response.json().catch(() => null);
    if (!response.ok) {
        throw new Error(payload?.message || `HTTP ${response.status}`);
    }
    if (payload && payload.success === false) {
        throw new Error(payload.message || "request failed");
    }
    return payload?.data ?? payload;
}

async function apiOrNull(path, options = {}) {
    try {
        return await api(path, options);
    } catch (error) {
        if (error.status !== 404 && !/not found/i.test(error.message || "")) {
            console.warn(`optional api unavailable: ${path}`, error);
        }
        return null;
    }
}

function delay(ms) {
    return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function startPolling() {
    clearInterval(state.pollingTimer);
    state.pollingTimer = setInterval(async () => {
        if (document.hidden) {
            return;
        }
        try {
            await loadHealth();
            await loadSessions();
            await loadTasks();
            if (state.selectedTaskId) {
                await loadSelectedTask(state.selectedTaskId, false);
                await loadMessages(taskSessionId(selectedTask()) || state.selectedSessionId);
            } else {
                await loadMessages();
            }
        } catch (error) {
            console.error(error);
        }
    }, 5000);
}

function closeModal() {
    dom.taskDetailModal.style.display = "none";
    document.body.style.overflow = "";
}

async function openTaskDetailModal(taskId) {
    const flow = await api(`/api/v1/tasks/${encodeURIComponent(taskId)}/live_flow?limit=8`);
    const task = flow?.task;
    
    if (!task) {
        showToast("无法获取任务详情", true);
        return;
    }
    
    dom.modalTitle.textContent = task.title || task.id;
    document.body.style.overflow = "hidden";
    
    dom.modalTaskInfo.innerHTML = `
        <div class="info-item">
            <label>任务 ID</label>
            <value>${escapeHtml(task.id)}</value>
        </div>
        <div class="info-item">
            <label>状态</label>
            <value>${escapeHtml(task.status || "active")}</value>
        </div>
        <div class="info-item">
            <label>控制节点</label>
            <value>${escapeHtml(task.control_node || task.controlNode || "intake")}</value>
        </div>
        <div class="info-item">
            <label>Worker</label>
            <value>${escapeHtml(task.assigned_worker || task.assignedWorker || "unassigned")}</value>
        </div>
        <div class="info-item">
            <label>优先级</label>
            <value>${escapeHtml(task.priority || "high")}</value>
        </div>
        <div class="info-item">
            <label>任务类型</label>
            <value>${escapeHtml(task.task_type || task.taskType || "general")}</value>
        </div>
        <div class="info-item">
            <label>创建时间</label>
            <value>${escapeHtml(formatTime(task.created_at || task.createdAt))}</value>
        </div>
        <div class="info-item">
            <label>更新时间</label>
            <value>${escapeHtml(formatTime(task.updated_at || task.updatedAt))}</value>
        </div>
    `;
    
    const fullContent = firstNonBlank(
        assistantOutputFullContent(task, flow),
        failureNarrativeFallback(flow?.task?.metadata || task?.metadata || {}),
        flow?.runtime_context?.active_context?.continuity_summary,
        flow?.runtimeContext?.activeContext?.continuitySummary,
        flow?.judgment_trace?.completion_judgment?.summary,
        flow?.judgmentTrace?.completionJudgment?.summary,
        flow?.judgment_trace?.execution_judgment?.summary,
        flow?.judgmentTrace?.executionJudgment?.summary,
        task.summary,
        task.next_step,
        task.nextStep,
        task.intent,
        "暂无完整内容"
    );
    dom.modalFullContent.textContent = fullContent;
    
    const routePreview = flow?.route_preview || flow?.routePreview;
    if (routePreview) {
        dom.modalRouteInfo.innerHTML = `
            <div class="info-grid" style="grid-template-columns: 1fr;">
                <div class="info-item">
                    <label>路由来源</label>
                    <value>${escapeHtml(routePreview.route_source || routePreview.routeSource || "unknown")}</value>
                </div>
                <div class="info-item">
                    <label>选中 Worker</label>
                    <value>${escapeHtml(routePreview.selected_worker || routePreview.selectedWorker || "unknown")}</value>
                </div>
                <div class="info-item">
                    <label>选择原因</label>
                    <value>${escapeHtml(routePreview.why_selected || routePreview.whySelected || routePreview.route_reason || routePreview.routeReason || "not specified")}</value>
                </div>
                <div class="info-item">
                    <label>候选 Workers</label>
                    <value>${escapeHtml((routePreview.candidate_workers || routePreview.candidateWorkers || []).join(", ") || "none")}</value>
                </div>
            </div>
        `;
    } else {
        dom.modalRouteInfo.innerHTML = emptyState("暂无路由信息");
    }
    
    const decisions = flow?.decisions || [];
    const judgmentTrace = flow?.judgment_trace || flow?.judgmentTrace || {};
    const executionJudgment = judgmentTrace.execution_judgment || judgmentTrace.executionJudgment;
    const completionJudgment = judgmentTrace.completion_judgment || judgmentTrace.completionJudgment;
    
    const decisionItems = [];
    if (executionJudgment) {
        decisionItems.push(`
            <div class="info-item">
                <label>执行判断</label>
                <value>${escapeHtml(executionJudgment.action || executionJudgment.status || "unknown")}</value>
            </div>
        `);
    }
    if (completionJudgment) {
        decisionItems.push(`
            <div class="info-item">
                <label>完成判断</label>
                <value>${escapeHtml(completionJudgment.status || "unknown")}</value>
            </div>
        `);
    }
    decisions.forEach((decision, index) => {
        decisionItems.push(`
            <div class="info-item">
                <label>决策 ${index + 1}</label>
                <value>${escapeHtml(decision.decision_type || decision.decisionType || "decision")}: ${escapeHtml(decision.result || decision.summary || "no result")}</value>
            </div>
        `);
    });
    
    dom.modalDecisions.innerHTML = decisionItems.length > 0 
        ? `<div class="info-grid" style="grid-template-columns: 1fr;">${decisionItems.join("")}</div>`
        : emptyState("暂无决策记录");
    
    const tools = flow?.tool_invocations || flow?.toolInvocations || [];
    if (tools.length > 0) {
        dom.modalTools.innerHTML = tools.map((tool) => `
            <div class="info-item">
                <label>${escapeHtml(tool.tool_name || tool.toolName || "tool")}</label>
                <value>${escapeHtml(toolTraceStatusLabel(tool))} · ${escapeHtml(toolTraceSummary(tool))}</value>
            </div>
        `).join("");
    } else {
        dom.modalTools.innerHTML = emptyState("暂无工具调用记录");
    }

    renderProviderRunFiles(flow);
    
    dom.taskDetailModal.style.display = "flex";
}

function renderProviderRunFiles(flow) {
    if (!dom.modalProviderRunFiles) {
        return;
    }
    const plan = buildProviderRunFilePlan(flow);
    if (!plan.files || plan.files.length === 0) {
        dom.modalProviderRunFiles.innerHTML = emptyState("暂无 provider run 文件");
        return;
    }
    const runDir = plan.runDir ? `
        <div class="provider-run-files__path">
            <span class="task-badge">run dir</span>
            <code>${escapeHtml(plan.runDir)}</code>
        </div>
    ` : "";
    const buttons = plan.files.map((file) => `
        <button class="button button--ghost button--compact"
                type="button"
                title="${escapeHtml(file.path)}"
                data-provider-run-task-id="${escapeHtml(plan.taskId)}"
                data-provider-run-kind="${escapeHtml(file.kind)}">
            ${escapeHtml(file.label)}
        </button>
    `).join("");
    dom.modalProviderRunFiles.innerHTML = `
        <div class="provider-run-files">
            ${runDir}
            <div class="provider-run-files__actions">${buttons}</div>
            <pre class="provider-run-files__preview" data-provider-run-preview>选择文件预览内容</pre>
        </div>
    `;
}

async function onProviderRunFileClick(event) {
    const button = event.target.closest("[data-provider-run-kind]");
    if (!button) {
        return;
    }
    const taskId = button.dataset.providerRunTaskId;
    const kind = button.dataset.providerRunKind;
    const previewBox = dom.modalProviderRunFiles?.querySelector("[data-provider-run-preview]");
    if (!taskId || !kind || !previewBox) {
        return;
    }
    button.disabled = true;
    previewBox.textContent = "读取中...";
    try {
        const file = await api(`/api/v1/tasks/${encodeURIComponent(taskId)}/provider_run_file?kind=${encodeURIComponent(kind)}`);
        const meta = [
            file.kind || kind,
            file.path,
            file.size_bytes || file.sizeBytes ? `${file.size_bytes || file.sizeBytes} bytes` : null,
            file.truncated ? `truncated at ${file.limit_bytes || file.limitBytes || "limit"} bytes` : null
        ].filter(Boolean).join(" · ");
        previewBox.textContent = [meta, file.content || ""].filter(Boolean).join("\n\n");
    } finally {
        button.disabled = false;
    }
}
