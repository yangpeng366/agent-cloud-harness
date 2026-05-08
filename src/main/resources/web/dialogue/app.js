const state = {
    sessions: [],
    tasks: [],
    messages: [],
    relatedMessages: [],
    messageFilterRole: "all",
    messageFilterScope: "all",
    workers: [],
    selectedSessionId: null,
    selectedTaskId: null,
    followupParentTaskId: null,
    liveFlow: null,
    experimentSummary: null,
    toastTimer: null,
    pollingTimer: null
};

const dom = {
    healthBadge: document.getElementById("healthBadge"),
    sessionCount: document.getElementById("sessionCount"),
    taskCount: document.getElementById("taskCount"),
    chainCount: document.getElementById("chainCount"),
    selectedStatus: document.getElementById("selectedStatus"),
    sessionList: document.getElementById("sessionList"),
    sessionForm: document.getElementById("sessionForm"),
    sessionTitle: document.getElementById("sessionTitle"),
    refreshSessionsButton: document.getElementById("refreshSessionsButton"),
    heroTitle: document.getElementById("heroTitle"),
    heroSubtitle: document.getElementById("heroSubtitle"),
    messagePanelHint: document.getElementById("messagePanelHint"),
    messageRoleFilters: document.getElementById("messageRoleFilters"),
    messageScopeFilters: document.getElementById("messageScopeFilters"),
    messageSummary: document.getElementById("messageSummary"),
    messageList: document.getElementById("messageList"),
    messageForm: document.getElementById("messageForm"),
    messageContent: document.getElementById("messageContent"),
    messageAttachTask: document.getElementById("messageAttachTask"),
    clearMessageButton: document.getElementById("clearMessageButton"),
    messageSessionLabel: document.getElementById("messageSessionLabel"),
    messageHint: document.getElementById("messageHint"),
    threadHint: document.getElementById("threadHint"),
    refreshThreadButton: document.getElementById("refreshThreadButton"),
    taskThread: document.getElementById("taskThread"),
    taskForm: document.getElementById("taskForm"),
    taskTitle: document.getElementById("taskTitle"),
    taskType: document.getElementById("taskType"),
    taskPriority: document.getElementById("taskPriority"),
    taskGoal: document.getElementById("taskGoal"),
    taskAutoStart: document.getElementById("taskAutoStart"),
    taskIntent: document.getElementById("taskIntent"),
    composerSessionLabel: document.getElementById("composerSessionLabel"),
    composerTaskHint: document.getElementById("composerTaskHint"),
    followupButton: document.getElementById("followupButton"),
    clearFollowupButton: document.getElementById("clearFollowupButton"),
    detailTitle: document.getElementById("detailTitle"),
    taskOverview: document.getElementById("taskOverview"),
    taskActions: document.getElementById("taskActions"),
    handoffWorker: document.getElementById("handoffWorker"),
    handoffButton: document.getElementById("handoffButton"),
    chainContext: document.getElementById("chainContext"),
    continuitySummary: document.getElementById("continuitySummary"),
    continuityChips: document.getElementById("continuityChips"),
    routeBox: document.getElementById("routeBox"),
    experimentSummary: document.getElementById("experimentSummary"),
    decisionList: document.getElementById("decisionList"),
    artifactList: document.getElementById("artifactList"),
    relatedMessages: document.getElementById("relatedMessages"),
    toolList: document.getElementById("toolList"),
    toast: document.getElementById("toast")
};

document.addEventListener("DOMContentLoaded", () => {
    bindEvents();
    init().catch((error) => {
        console.error(error);
        showToast(error.message || "dialogue init failed", true);
    });
});

function bindEvents() {
    dom.sessionForm.addEventListener("submit", onCreateSession);
    dom.refreshSessionsButton.addEventListener("click", () => refreshAll(true));
    dom.refreshThreadButton.addEventListener("click", () => {
        if (state.selectedTaskId) {
            loadSelectedTask(state.selectedTaskId, true).catch(handleError);
            return;
        }
        refreshAll(true).catch(handleError);
    });
    dom.messageForm.addEventListener("submit", onCreateMessage);
    dom.messageAttachTask.addEventListener("change", renderMessageComposerContext);
    dom.clearMessageButton.addEventListener("click", onClearMessage);
    dom.messageRoleFilters.addEventListener("click", onMessageFilterClick);
    dom.messageScopeFilters.addEventListener("click", onMessageFilterClick);
    dom.messageList.addEventListener("click", onMessageActionClick);
    dom.relatedMessages.addEventListener("click", onMessageActionClick);
    dom.taskForm.addEventListener("submit", onCreateTask);
    dom.followupButton.addEventListener("click", onFollowupDraft);
    dom.clearFollowupButton.addEventListener("click", onClearFollowup);
    dom.taskThread.addEventListener("click", onThreadClick);
    dom.taskThread.addEventListener("keydown", onThreadKeydown);
    dom.chainContext.addEventListener("click", onChainContextClick);
    dom.taskActions.addEventListener("click", onTaskActionClick);
    dom.handoffButton.addEventListener("click", onHandoff);
    window.addEventListener("hashchange", () => {
        applyLocationSelection();
        refreshAll(false).catch(handleError);
    });
}

async function init() {
    applyLocationSelection();
    await Promise.all([loadHealth(), loadWorkers()]);
    await refreshAll(false);
    startPolling();
}

async function refreshAll(loud) {
    await loadSessions();
    await loadTasks();
    await loadMessages();
    if (state.selectedTaskId) {
        await loadSelectedTask(state.selectedTaskId, false);
    } else if (state.tasks.length > 0) {
        await selectTask(state.tasks[state.tasks.length - 1].id, false);
    } else {
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
        .sort((a, b) => new Date(b.updated_at || b.updatedAt || 0) - new Date(a.updated_at || a.updatedAt || 0));

    if (!state.selectedSessionId || !state.sessions.some((session) => session.id === state.selectedSessionId)) {
        state.selectedSessionId = state.sessions[0]?.id ?? null;
    }

    dom.sessionCount.textContent = String(state.sessions.length);
    const currentSession = state.sessions.find((session) => session.id === state.selectedSessionId);
    dom.composerSessionLabel.textContent = currentSession?.title || "自动创建";
    dom.messageSessionLabel.textContent = currentSession?.title || "自动创建";
    dom.heroTitle.textContent = currentSession
        ? `会话：${currentSession.title}`
        : "把 task 当作对话发给 harness";
    dom.heroSubtitle.textContent = currentSession
        ? "这里更像 chat 工作台，但底层仍然是 session messages、task chain 和 live flow。"
        : "先创建会话，或直接记录第一条消息/第一条任务让系统自动创建 session。";
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
        .sort((a, b) => new Date(a.created_at || a.createdAt || 0) - new Date(b.created_at || b.createdAt || 0));

    if (state.selectedTaskId && !state.tasks.some((task) => task.id === state.selectedTaskId)) {
        state.selectedTaskId = state.tasks[state.tasks.length - 1]?.id ?? null;
    }
    if (state.followupParentTaskId && !state.tasks.some((task) => task.id === state.followupParentTaskId)) {
        state.followupParentTaskId = null;
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
        renderMessages();
        renderMessageComposerContext();
        return;
    }

    const messages = await api(`/api/v1/sessions/${encodeURIComponent(sessionId)}/messages?limit=80`);
    state.messages = messages
        .slice()
        .sort((a, b) => new Date(a.created_at || a.createdAt || 0) - new Date(b.created_at || b.createdAt || 0));
    renderMessages();
    renderMessageComposerContext();
}

async function loadRelatedMessages(task) {
    const taskId = task?.id;
    const sessionId = taskSessionId(task) || state.selectedSessionId;
    if (!taskId || !sessionId) {
        state.relatedMessages = [];
        return;
    }

    const messages = await api(
        `/api/v1/sessions/${encodeURIComponent(sessionId)}/messages?limit=20&task_id=${encodeURIComponent(taskId)}`
    );
    state.relatedMessages = messages
        .slice()
        .sort((a, b) => new Date(b.created_at || b.createdAt || 0) - new Date(a.created_at || a.createdAt || 0));
}

function applyRelatedMessagesFromLiveFlow(flow) {
    const messages = flow?.related_messages || flow?.relatedMessages;
    if (!Array.isArray(messages)) {
        return false;
    }
    state.relatedMessages = messages
        .slice()
        .sort((a, b) => new Date(b.created_at || b.createdAt || 0) - new Date(a.created_at || a.createdAt || 0));
    return true;
}

async function loadSelectedTask(taskId, loud) {
    const liveFlow = await api(`/api/v1/tasks/${encodeURIComponent(taskId)}/live_flow?limit=8`);
    const task = liveFlow?.task || null;
    const sessionId = taskSessionId(task);
    if (sessionId && sessionId !== state.selectedSessionId) {
        state.selectedSessionId = sessionId;
        await loadSessions();
        await loadTasks();
        await loadMessages(sessionId);
    }

    state.liveFlow = liveFlow;
    state.experimentSummary = await loadTaskExperimentSummary(taskId, liveFlow);
    state.selectedTaskId = taskId;
    if (!applyRelatedMessagesFromLiveFlow(liveFlow)) {
        await loadRelatedMessages(task);
    }
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
    await loadSessions();
    state.selectedSessionId = session.id;
    await loadTasks();
    await loadMessages(session.id);
    renderSessions();
    showToast(`已创建会话 ${session.title || session.id}`);
}

async function onCreateTask(event) {
    event.preventDefault();
    const intent = dom.taskIntent.value.trim();
    if (!intent) {
        showToast("请先填写任务说明", true);
        dom.taskIntent.focus();
        return;
    }

    const followupParentTaskId = state.followupParentTaskId;
    const goal = dom.taskGoal.value.trim() || null;
    const autoStart = dom.taskAutoStart.checked;
    const body = {
        title: dom.taskTitle.value.trim() || deriveTitle(intent),
        task_type: dom.taskType.value,
        source: "user",
        priority: dom.taskPriority.value,
        intent,
        goal,
        parent_task_id: followupParentTaskId,
        auto_start: autoStart,
        session_id: state.selectedSessionId,
        metadata: {
            source_surface: "web_dialogue",
            created_via: "dialogue_workspace",
            ...(followupParentTaskId ? { followup_parent_task_id: followupParentTaskId } : {})
        }
    };

    const task = await api("/api/v1/tasks", {
        method: "POST",
        body: JSON.stringify(body)
    });

    dom.taskTitle.value = "";
    dom.taskGoal.value = "";
    dom.taskIntent.value = "";
    dom.taskAutoStart.checked = true;
    state.followupParentTaskId = null;

    const sessionId = taskSessionId(task);
    if (!state.selectedSessionId || sessionId !== state.selectedSessionId) {
        state.selectedSessionId = sessionId;
        await loadSessions();
    }

    try {
        await mirrorTaskAsMessage(task, {
            intent,
            goal,
            autoStart,
            followupParentTaskId
        });
    } catch (error) {
        console.warn("task mirror message failed", error);
    }

    await loadTasks();
    await loadMessages(sessionId);
    await selectTask(task.id, false);
    showToast(`任务已发布：${task.title}`);
}

async function onCreateMessage(event) {
    event.preventDefault();
    const content = dom.messageContent.value.trim();
    if (!content) {
        showToast("请先填写消息内容", true);
        dom.messageContent.focus();
        return;
    }

    const session = await ensureSessionForMessage(content);
    const task = dom.messageAttachTask.checked ? selectedTask() : null;
    const message = await api(`/api/v1/sessions/${encodeURIComponent(session.id)}/messages`, {
        method: "POST",
        body: JSON.stringify({
            role: "user",
            message_type: task ? "task_note" : "user_note",
            content,
            task_id: task?.id || null,
            metadata: {
                source_surface: "web_dialogue",
                created_via: "dialogue_workspace",
                attached_task: Boolean(task),
                ...(task ? { attached_task_id: task.id } : {})
            }
        })
    });

    dom.messageContent.value = "";
    await loadSessions();
    await loadTasks();
    await loadMessages(session.id);
    if (task && task.id === state.selectedTaskId) {
        await loadRelatedMessages(task);
    } else if (!task) {
        state.relatedMessages = [];
    }
    renderDetails();
    showToast(`已记录消息：${formatMessageType(message.message_type || message.messageType)}`);
}

function onClearMessage() {
    dom.messageContent.value = "";
    dom.messageContent.focus();
    showToast("已清空消息草稿");
}

function onMessageActionClick(event) {
    const button = event.target.closest("[data-message-action]");
    if (!button) {
        return;
    }

    const message = messageById(button.dataset.messageId);
    if (!message) {
        showToast("消息不存在或已过期", true);
        return;
    }

    const action = button.dataset.messageAction;
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
    if (!button || !state.selectedTaskId) {
        return;
    }

    const action = button.dataset.taskAction;
    await api(`/api/v1/tasks/${encodeURIComponent(state.selectedTaskId)}/${action}`, {
        method: "POST",
        body: "{}"
    });
    await loadTasks();
    await loadSelectedTask(state.selectedTaskId, false);
    await loadMessages(taskSessionId(selectedTask()) || state.selectedSessionId);
    showToast(`已执行 ${action}`);
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
    const task = selectedTask();
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
    state.followupParentTaskId = task.id;
    renderComposerContext();
    dom.taskIntent.focus();
    dom.taskIntent.setSelectionRange(dom.taskIntent.value.length, dom.taskIntent.value.length);
    showToast(`已生成 ${task.title || task.id} 的 follow-up 草稿`);
}

function onClearFollowup() {
    state.followupParentTaskId = null;
    renderComposerContext();
    showToast("已清除 follow-up 关联");
}

function onThreadClick(event) {
    const taskCard = event.target.closest("[data-task-id]");
    if (!taskCard) {
        return;
    }
    selectTask(taskCard.dataset.taskId, false).catch(handleError);
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
        dom.sessionList.innerHTML = emptyState("还没有 session。直接发布第一条任务也会自动创建。");
        return;
    }

    dom.sessionList.innerHTML = state.sessions.map((session) => {
        const active = session.id === state.selectedSessionId ? "is-active" : "";
        return `
            <button class="session-card ${active}" data-session-id="${escapeHtml(session.id)}" type="button">
                <div class="session-card__title">${escapeHtml(session.title || session.id)}</div>
                <div class="session-card__meta">
                    <span class="task-badge" data-tone="${toneForStatus(session.status)}">${escapeHtml(session.status || "active")}</span>
                    <span>${formatTime(session.updated_at || session.updatedAt)}</span>
                </div>
                <div class="session-card__meta mono">${escapeHtml(session.id)}</div>
            </button>
        `;
    }).join("");

    dom.sessionList.querySelectorAll("[data-session-id]").forEach((button) => {
        button.addEventListener("click", async () => {
            state.selectedSessionId = button.dataset.sessionId;
            await loadSessions();
            await loadTasks();
            await loadMessages(state.selectedSessionId);
            if (state.tasks.length > 0) {
                await selectTask(state.tasks[state.tasks.length - 1].id, false);
            } else {
                state.selectedTaskId = null;
                state.liveFlow = null;
                state.relatedMessages = [];
                renderMessages();
                renderThread();
                renderDetails();
                renderMessageComposerContext();
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
    dom.messageSummary.innerHTML = renderMessageSummary(filteredMessages);
    dom.messagePanelHint.textContent = state.messages.length > 0
        ? `当前 session 共 ${state.messages.length} 条消息，当前筛出 ${filteredMessages.length} 条${describeMessageFilterSummary()}。`
        : "当前 session 还没有消息，可以先记上下文，再发布 task。";
    dom.messageList.innerHTML = filteredMessages.length > 0
        ? filteredMessages.map((message) => renderMessageCard(message)).join("")
        : emptyState(emptyMessageFilterText());
}

function renderThread() {
    if (state.tasks.length === 0) {
        dom.taskThread.innerHTML = emptyState("当前会话还没有任务。把下面的输入区当作 chat composer 来用。");
        return;
    }

    const tasksById = mapById(state.tasks);
    const chains = buildTaskChains(state.tasks);
    dom.taskThread.innerHTML = chains.map((chain, chainIndex) => {
        const selectedInChain = chain.tasks.some((task) => task.id === state.selectedTaskId) ? "is-active" : "";
        const rootTask = chain.rootTask || chain.tasks[0];
        const latestTask = chain.latestTask || chain.tasks[chain.tasks.length - 1];
        return `
            <section class="dialogue-chain ${selectedInChain}">
                <header class="dialogue-chain__header">
                    <div>
                        <p class="eyebrow">Iteration Chain ${String(chainIndex + 1).padStart(2, "0")}</p>
                        <h3 class="dialogue-chain__title">${escapeHtml(rootTask?.title || chain.rootId)}</h3>
                    </div>
                    <div class="dialogue-chain__meta">
                        <span class="task-badge">${escapeHtml(`${chain.tasks.length} tasks`)}</span>
                        <span class="task-badge" data-tone="${toneForStatus(latestTask?.status)}">${escapeHtml(latestTask?.status || "active")}</span>
                        <span class="task-badge">${escapeHtml(latestTask?.control_node || latestTask?.controlNode || "intake")}</span>
                        ${renderStartModeBadge(latestTask)}
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
                    <div class="dialogue-task__meta">
                        <span class="dialogue-task__role">Harness</span>
                        <span class="task-badge" data-tone="${toneForStatus(task.status)}">${escapeHtml(task.status || "active")}</span>
                        <span class="task-badge">${escapeHtml(task.control_node || task.controlNode || "intake")}</span>
                        ${renderStartModeBadge(task)}
                        ${task.assigned_worker || task.assignedWorker ? `<span class="task-badge">${escapeHtml(task.assigned_worker || task.assignedWorker)}</span>` : ""}
                    </div>
                    <div class="dialogue-task__body">${escapeHtml(buildAssistantMessage(task, flow))}</div>
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
    if (!task) {
        dom.detailTitle.textContent = "选择一个任务";
        dom.selectedStatus.textContent = "idle";
        dom.taskOverview.innerHTML = emptyState("当前未选中任务。");
        dom.chainContext.innerHTML = emptyState("当前没有迭代链。");
        dom.relatedMessages.innerHTML = emptyState("选中一个任务后，这里会显示它关联的 session messages。");
        dom.continuitySummary.innerHTML = "选中一个任务后，这里会显示 active context 和 continuity 摘要。";
        dom.continuityChips.innerHTML = "";
        dom.routeBox.innerHTML = emptyState("暂无 route preview");
        dom.experimentSummary.innerHTML = emptyState("当前任务不属于 experiment batch。");
        dom.decisionList.innerHTML = emptyState("暂无 decision");
        dom.artifactList.innerHTML = emptyState("暂无 artifact");
        dom.toolList.innerHTML = emptyState("暂无 tool trace");
        setTaskActionState(false);
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

    dom.detailTitle.textContent = task.title || task.id;
    dom.selectedStatus.textContent = `${task.status || "active"} / ${task.control_node || task.controlNode || "intake"}`;
    dom.taskOverview.innerHTML = [
        overviewCard("任务 ID", task.id),
        overviewCard("状态", task.status || "active"),
        overviewCard("控制节点", task.control_node || task.controlNode || "intake"),
        overviewCard("Worker", task.assigned_worker || task.assignedWorker || "unassigned"),
        overviewCard("实验模式", humanizeToken(experimentMode) || experimentMode),
        overviewCard("Tool chain", toolLabel || "none")
    ].join("");

    dom.chainContext.innerHTML = renderChainContext(task);
    dom.relatedMessages.innerHTML = state.relatedMessages.length > 0
        ? state.relatedMessages.map((message) => renderMessageCard(message, { compact: true, relatedOnly: true })).join("")
        : emptyState("当前任务还没有关联消息。");
    dom.continuitySummary.textContent = continuitySummary || "暂无 continuity summary";
    dom.continuityChips.innerHTML = [
        ...toChipLines("open", openQuestions),
        ...toChipLines("next", nextCandidates)
    ].map((line) => `<span class="chip">${escapeHtml(line)}</span>`).join("");

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
        ? recentArtifacts.map((artifact) => stackItem(
            artifact.artifact_type || artifact.artifactType || "artifact",
            artifact.title || "untitled artifact",
            preview(artifact.summary || artifact.uri || "", 220),
            formatTime(artifact.created_at || artifact.createdAt)
        )).join("")
        : emptyState("暂无 artifact");

    const toolCards = tools.map((tool) => stackItem(
            tool.tool_name || tool.toolName || "tool",
            tool.success ? "success" : "failed",
            preview(tool.result_summary || tool.resultSummary || "", 220),
            `${formatTime(tool.created_at || tool.createdAt)}${tool.elapsed_ms || tool.elapsedMs ? ` · ${tool.elapsed_ms || tool.elapsedMs} ms` : ""}`
        ));
    if (toolSummary) {
        toolCards.unshift(renderToolChainSummaryCard(toolFacts, toolLabel, toolSummary));
    }
    dom.toolList.innerHTML = toolCards.length > 0 ? toolCards.join("") : emptyState("当前任务还没有 tool trace");

    setTaskActionState(true);
    renderComposerContext();
}

function renderWorkerOptions() {
    const options = state.workers.map((worker) => {
        const workerId = worker.worker_id || worker.workerId;
        return `<option value="${escapeHtml(workerId)}">${escapeHtml(workerId)}</option>`;
    });
    dom.handoffWorker.innerHTML = options.join("");
    dom.handoffButton.disabled = options.length === 0;
}

function renderMessageCard(message, options = {}) {
    const role = normalizeMessageRole(message.role);
    const type = normalizeMessageType(message.message_type || message.messageType);
    const taskId = messageTaskId(message);
    const isRelated = taskId && taskId === state.selectedTaskId;
    const compact = options.compact === true;
    const body = preview(message.content || "", compact ? 180 : 280);
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
        `message-card--${role}`
    ].filter(Boolean).join(" ");

    return `
        <article class="${classes}">
            <div class="message-card__meta">
                <span class="task-badge" data-tone="${messageRoleTone(role)}">${escapeHtml(formatMessageRole(role))}</span>
                <span class="task-badge">${escapeHtml(formatMessageType(type))}</span>
                ${renderMessageSignals(message)}
                ${taskId ? `<span class="task-badge" data-tone="${isRelated ? "active" : "default"}">${escapeHtml(`task · ${preview(taskId, 18)}`)}</span>` : ""}
                <span>${formatTime(message.created_at || message.createdAt)}</span>
            </div>
            <div class="message-card__body">${escapeHtml(body)}</div>
            ${options.relatedOnly && taskId ? `<div class="message-card__hint mono">${escapeHtml(taskId)}</div>` : ""}
            ${actions.length > 0 ? `<div class="message-card__actions">${actions.join("")}</div>` : ""}
        </article>
    `;
}

function renderMessageComposerContext() {
    const session = currentSession();
    const task = selectedTask();

    dom.messageSessionLabel.textContent = session?.title || "自动创建";
    dom.messageAttachTask.disabled = !task;
    if (!task) {
        dom.messageAttachTask.checked = false;
    }

    const sessionLine = session
        ? `当前 session：${session.title || session.id}`
        : "当前未锁定 session，提交第一条消息时会自动创建。";
    const taskLine = task
        ? (dom.messageAttachTask.checked
            ? `消息会关联 task：${task.title || task.id}`
            : `当前 task：${task.title || task.id}，但本条消息只写入 session。`)
        : "当前没有选中 task，本条消息不会附着到任务。";
    dom.messageHint.textContent = [sessionLine, taskLine].join(" · ");
}

function renderMessageSummary(messages) {
    const summaries = ["assistant", "system"]
        .map((role) => summarizeMessageRole(messages, role))
        .filter(Boolean);
    if (summaries.length === 0) {
        return "";
    }
    return summaries.map((summary) => renderMessageSummaryCard(summary)).join("");
}

function summarizeMessageRole(messages, role) {
    const roleMessages = (messages || []).filter((message) => normalizeMessageRole(message?.role) === role);
    if (roleMessages.length === 0) {
        return null;
    }

    const latest = roleMessages[roleMessages.length - 1];
    const typeCounts = new Map();
    roleMessages.forEach((message) => {
        const type = normalizeMessageType(message.message_type || message.messageType) || "message";
        typeCounts.set(type, (typeCounts.get(type) || 0) + 1);
    });

    const topTypes = [...typeCounts.entries()]
        .sort((left, right) => right[1] - left[1])
        .slice(0, 3)
        .map(([type, count]) => `${formatMessageType(type)} × ${count}`);
    const metadata = latest?.metadata || {};
    return {
        role,
        count: roleMessages.length,
        latestAt: latest?.created_at || latest?.createdAt,
        latestText: preview(latest?.content || "", 160),
        topTypes,
        latestTaskId: messageTaskId(latest),
        signals: messageSignalTexts(metadata).slice(0, 3)
    };
}

function renderMessageSummaryCard(summary) {
    return `
        <section class="message-summary-card" data-role="${escapeHtml(summary.role)}">
            <div class="message-summary-card__meta">
                <span class="task-badge" data-tone="${messageRoleTone(summary.role)}">${escapeHtml(formatMessageRole(summary.role))}</span>
                <span class="task-badge">${escapeHtml(`${summary.count} messages`)}</span>
                ${summary.latestTaskId ? `<span class="task-badge">${escapeHtml(`latest task · ${preview(summary.latestTaskId, 18)}`)}</span>` : ""}
                ${summary.latestAt ? `<span>${escapeHtml(formatTime(summary.latestAt))}</span>` : ""}
            </div>
            <div class="message-summary-card__body">${escapeHtml(summary.latestText || "暂无可读摘要。")}</div>
            ${summary.signals.length > 0 ? `
                <div class="message-summary-card__signals">
                    ${summary.signals.map((line) => `<span class="signal">${escapeHtml(line)}</span>`).join("")}
                </div>
            ` : ""}
            ${summary.topTypes.length > 0 ? `
                <div class="message-summary-card__foot">
                    ${escapeHtml(`top types · ${summary.topTypes.join(" / ")}`)}
                </div>
            ` : ""}
        </section>
    `;
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

function renderMessageSignals(message) {
    const metadata = message?.metadata || {};
    return messageSignalEntries(metadata)
        .map((entry) => signalBadge(entry.value, entry.tone, entry.label))
        .filter(Boolean)
        .slice(0, 4)
        .join("");
}

function messageSignalEntries(metadata = {}) {
    const route = messageRouteSignal(metadata);
    const tools = messageToolSignal(metadata);
    const modelMode = firstNonBlank(metadata.model_mode, metadata.modelMode);
    const preferredWorkerHint = firstNonBlank(
        metadata.preferred_worker_hint,
        metadata.preferredWorkerHint
    );
    const learningHintApplied = booleanValue(
        metadata.learning_hint_applied,
        metadata.learningHintApplied
    );
    return [
        { value: metadata.trigger, tone: "default", label: "trigger" },
        { value: route, tone: "active", label: "route" },
        { value: tools, tone: "default", label: "tools" },
        { value: metadata.completion_status || metadata.completionStatus, tone: "done", label: "completion" },
        { value: metadata.acceptance_result || metadata.acceptanceResult, tone: "done", label: "accept" },
        { value: metadata.judgment_action || metadata.judgmentAction, tone: "auto", label: "action" },
        { value: modelMode ? humanizeToken(modelMode) || modelMode : null, tone: "active", label: "mode" },
        { value: messageLearningSignal(preferredWorkerHint, learningHintApplied), tone: "auto", label: "hint" }
    ].filter((entry) => firstNonBlank(entry.value));
}

function messageSignalTexts(metadata = {}) {
    return messageSignalEntries(metadata)
        .map((entry) => signalText(entry.value, entry.label))
        .filter(Boolean);
}

function messageRouteSignal(metadata = {}) {
    const worker = firstNonBlank(
        metadata.selected_worker,
        metadata.selectedWorker,
        metadata.assigned_worker,
        metadata.assignedWorker,
        metadata.executor_worker,
        metadata.executorWorker
    );
    const source = firstNonBlank(
        metadata.route_source,
        metadata.routeSource
    );
    if (!worker) {
        return null;
    }
    return source ? `${worker} via ${humanizeToken(source) || source}` : worker;
}

function messageToolSignal(metadata = {}) {
    const traceSummary = firstNonBlank(
        metadata.tool_chain_trace_summary,
        metadata.toolChainTraceSummary
    );
    if (traceSummary) {
        return traceSummary.replace(/_/g, " ");
    }
    const stepCount = numberOrNull(
        metadata.tool_chain_step_count,
        metadata.toolChainStepCount
    );
    const terminationReason = firstNonBlank(
        metadata.tool_chain_termination_reason,
        metadata.toolChainTerminationReason
    );
    const executionMode = firstNonBlank(
        metadata.tool_execution_mode,
        metadata.toolExecutionMode
    );
    const parts = [
        stepCount ? formatCount(stepCount, "step") : null,
        terminationReason ? humanizeToken(terminationReason) || terminationReason : null,
        !terminationReason && executionMode ? humanizeToken(executionMode) || executionMode : null
    ].filter(Boolean);
    return parts.length > 0 ? parts.join(" · ") : null;
}

function messageLearningSignal(preferredWorkerHint, learningHintApplied) {
    if (!preferredWorkerHint && learningHintApplied == null) {
        return null;
    }
    if (preferredWorkerHint && learningHintApplied === true) {
        return `${preferredWorkerHint} applied`;
    }
    if (preferredWorkerHint && learningHintApplied === false) {
        return `${preferredWorkerHint} observed`;
    }
    if (learningHintApplied === true) {
        return "applied";
    }
    if (learningHintApplied === false) {
        return "observed";
    }
    return preferredWorkerHint;
}

function signalBadge(value, tone, label) {
    const text = firstNonBlank(value);
    if (!text) {
        return "";
    }
    return `<span class="task-badge" data-tone="${escapeHtml(tone || "default")}">${escapeHtml(`${label} · ${preview(text, 24)}`)}</span>`;
}

function signalText(value, label) {
    const text = firstNonBlank(value);
    return text ? `${label} · ${preview(text, 28)}` : null;
}

function overviewCard(label, value) {
    return `
        <div class="overview-card">
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(value || "n/a")}</strong>
        </div>
    `;
}

function decisionCard(type, decision, executionBoundary = null, runtimeFacts = null) {
    const diagnostics = judgmentDiagnosticFacts(decision, runtimeFacts, executionBoundary);
    const boundaryFacts = executionBoundaryFacts({ execution_boundary: executionBoundary }, []);
    const bodyParts = [
        preview(decision.rationale || decision.reason || "", 220),
        boundaryFacts.label || boundaryFacts.traceSummary
            ? `Execution: ${preview(boundaryFacts.traceSummary || boundaryFacts.label, 120)}`
            : null,
        diagnostics.metrics.length > 0 ? diagnostics.metrics.join(" · ") : null,
        diagnostics.cognitionRows.length > 0 ? diagnostics.cognitionRows.map((row) => `${row.label}: ${row.value}`).join(" · ") : null,
        diagnostics.alignmentChips.length > 0 ? diagnostics.alignmentChips.join(" · ") : null,
        diagnostics.candidateWorkers.length > 0 ? `Candidates: ${diagnostics.candidateWorkers.join(", ")}` : null,
        diagnostics.evidenceRefs.length > 0 ? `Evidence: ${diagnostics.evidenceRefs.join(" · ")}` : null,
        diagnostics.unfinishedItems.length > 0 ? `Unfinished: ${diagnostics.unfinishedItems.join(" · ")}` : null
    ].filter(Boolean);
    return stackItem(
        type,
        decision.summary || "no summary",
        bodyParts.join("\n"),
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

function buildUserMessage(task) {
    return preview(
        firstNonBlank(task.intent, task.metadata?.intent, task.goal, task.title, task.id) || task.id,
        280
    );
}

function buildAssistantMessage(task, flow) {
    const activeContext = flow?.runtime_context?.active_context || flow?.runtimeContext?.activeContext || {};
    const judgmentTrace = flow?.judgment_trace || flow?.judgmentTrace || {};
    const executionJudgment = judgmentTrace.execution_judgment || judgmentTrace.executionJudgment || {};
    const completionJudgment = judgmentTrace.completion_judgment || judgmentTrace.completionJudgment || {};
    return preview(
        firstNonBlank(
            activeContext.continuity_summary,
            activeContext.continuitySummary,
            task.summary,
            latestOutput(flow),
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
    return [
        valueLine("action", judgmentTrace.recommended_action || judgmentTrace.recommendedAction || executionJudgment.metadata?.action),
        valueLine("completion", completionJudgment.metadata?.completion_status || completionJudgment.metadata?.status),
        valueLine("route", routeSignal(flow)),
        valueLine("tools", toolChainLabel(flow)),
        valueLine("packet", latestPacket.active_task_summary || latestPacket.activeTaskSummary)
    ].filter(Boolean).slice(0, 4);
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
    if (!contextTask) {
        dom.composerTaskHint.textContent = "选择一个任务后，可以基于它的 next step 生成 follow-up 草稿。";
        dom.followupButton.disabled = true;
        dom.clearFollowupButton.disabled = true;
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
        ? `当前选中：${task.title || task.id} · ${firstNonBlank(task.status, "active")}/${firstNonBlank(task.control_node, task.controlNode, "intake")}`
        : null;
    const followupLine = followupParent
        ? `已绑定 follow-up：${followupParent.title || followupParent.id}`
        : "尚未绑定 follow-up 父任务。";
    const nextLine = nextStep ? `参考下一步：${preview(nextStep, 110)}` : null;

    dom.composerTaskHint.textContent = [selectedLine, followupLine, nextLine]
        .filter(Boolean)
        .join(" · ");
    dom.followupButton.disabled = !task;
    dom.clearFollowupButton.disabled = !followupParent;
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

async function mirrorTaskAsMessage(task, context) {
    const sessionId = taskSessionId(task);
    if (!sessionId) {
        return;
    }

    await api(`/api/v1/sessions/${encodeURIComponent(sessionId)}/messages`, {
        method: "POST",
        body: JSON.stringify({
            role: "user",
            message_type: context.followupParentTaskId ? "task_followup" : "task_brief",
            content: context.intent,
            task_id: task.id,
            metadata: {
                source_surface: "web_dialogue",
                created_via: "dialogue_workspace",
                mirrored_from: "task_form",
                auto_start: context.autoStart,
                task_title: task.title,
                ...(context.goal ? { goal: context.goal } : {}),
                ...(context.followupParentTaskId ? { parent_task_id: context.followupParentTaskId } : {})
            }
        })
    });
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
    const routeChips = [
        modelMode ? `mode: ${humanizeToken(modelMode) || modelMode}` : null,
        preferredWorkerHint ? `hint: ${preferredWorkerHint}` : null,
        learningHintApplied === true ? "learning: applied" : null,
        learningHintApplied === false ? "learning: observed, not applied" : null,
        routeAlignment === true ? "route/execution aligned" : null,
        routeAlignment === false ? "route/execution diverged" : null
    ].filter(Boolean);
    if (!selectedWorker && !routeReason && candidateWorkers.length === 0 && routeChips.length === 0) {
        return emptyState("暂无 route preview");
    }
    return `
        <div class="route-box">
            <div class="stack-item__meta">
                <span class="task-badge">${escapeHtml(selectedWorker)}</span>
                <span>${escapeHtml(routeSource)}</span>
                <span>${escapeHtml(taskType)}</span>
            </div>
            ${routeReason ? `<strong>${escapeHtml(preview(routeReason, 220))}</strong>` : ""}
            ${candidateWorkers.length > 0 ? `<p class="mono">${escapeHtml(candidateWorkers.join(", "))}</p>` : ""}
            ${routeChips.length > 0 ? `
                <div class="chip-list experiment-summary__chips">
                    ${routeChips.map((chip) => `<span class="chip">${escapeHtml(chip)}</span>`).join("")}
                </div>
            ` : ""}
            ${renderCognitionTimeline(cognitionTimeline)}
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
    return `
        <div class="experiment-summary">
            <div class="stack-item__meta">
                <span class="task-badge">${escapeHtml(experimentName)}</span>
                <span>${escapeHtml(firstNonBlank(experimentRun.task_title, experimentRun.taskTitle, taskCaseKey, "current task"))}</span>
            </div>
            <div class="chip-list experiment-summary__chips">
                ${summaryChips.map((chip) => `<span class="chip">${escapeHtml(chip)}</span>`).join("")}
            </div>
            ${summary ? `
                <div class="experiment-summary__grid">
                    ${modeSummaries.map((mode) => renderExperimentModeCard(mode, currentMode)).join("")}
                </div>
                ${currentCase ? `
                    <div class="experiment-summary__case-grid">
                        ${(supportedModes.length > 0 ? supportedModes : Object.keys(currentCase.runs_by_mode || currentCase.runsByMode || {}))
                            .map((mode) => renderExperimentCaseCard(mode, currentCase, currentMode))
                            .join("")}
                    </div>
                ` : emptyState("当前 task case 还没有三种 mode 对比。")}
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
    if (sessionId) {
        state.selectedSessionId = sessionId;
    }
    if (taskId) {
        state.selectedTaskId = taskId;
    }
}

function syncLocationSelection() {
    const params = new URLSearchParams();
    if (state.selectedSessionId) {
        params.set("session", state.selectedSessionId);
    }
    if (state.selectedTaskId) {
        params.set("task", state.selectedTaskId);
    }
    const nextHash = params.toString() ? `#${params.toString()}` : "";
    if (window.location.hash === nextHash) {
        return;
    }
    window.history.replaceState(null, "", `${window.location.pathname}${window.location.search}${nextHash}`);
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

    const previousTask = currentIndex > 0 ? chain.tasks[currentIndex - 1] : null;
    const nextTask = currentIndex < chain.tasks.length - 1 ? chain.tasks[currentIndex + 1] : null;
    const currentRound = currentIndex === 0 ? "root" : `round ${currentIndex + 1}`;
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
            <div class="chain-context__list">
                ${chain.tasks.map((item, index) => {
                    const active = item.id === task.id ? "is-active" : "";
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
                }).join("")}
            </div>
        </div>
    `;
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
    const date = new Date(task?.created_at || task?.createdAt || 0);
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
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return String(value);
    }
    return date.toLocaleString("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
    });
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
    dom.handoffButton.disabled = !enabled || state.workers.length === 0;
    dom.refreshThreadButton.disabled = false;
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
            await loadMessages();
            if (state.selectedTaskId) {
                await loadSelectedTask(state.selectedTaskId, false);
            }
        } catch (error) {
            console.error(error);
        }
    }, 5000);
}
