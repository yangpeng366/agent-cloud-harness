const state = {
    sessions: [],
    tasks: [],
    workers: [],
    selectedSessionId: null,
    selectedTaskId: null,
    followupParentTaskId: null,
    liveFlow: null,
    toastTimer: null,
    pollingTimer: null
};

const dom = {
    healthBadge: document.getElementById("healthBadge"),
    sessionCount: document.getElementById("sessionCount"),
    taskCount: document.getElementById("taskCount"),
    pollingState: document.getElementById("pollingState"),
    sessionList: document.getElementById("sessionList"),
    sessionForm: document.getElementById("sessionForm"),
    sessionTitle: document.getElementById("sessionTitle"),
    refreshSessionsButton: document.getElementById("refreshSessionsButton"),
    taskTimeline: document.getElementById("taskTimeline"),
    timelineHint: document.getElementById("timelineHint"),
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
    inspectorTitle: document.getElementById("inspectorTitle"),
    taskOverview: document.getElementById("taskOverview"),
    taskActions: document.getElementById("taskActions"),
    chainContext: document.getElementById("chainContext"),
    continuitySummary: document.getElementById("continuitySummary"),
    continuityChips: document.getElementById("continuityChips"),
    routeBox: document.getElementById("routeBox"),
    decisionList: document.getElementById("decisionList"),
    artifactList: document.getElementById("artifactList"),
    toolList: document.getElementById("toolList"),
    rawJson: document.getElementById("rawJson"),
    refreshTaskButton: document.getElementById("refreshTaskButton"),
    handoffWorker: document.getElementById("handoffWorker"),
    handoffButton: document.getElementById("handoffButton"),
    heroTitle: document.getElementById("heroTitle"),
    heroSubtitle: document.getElementById("heroSubtitle"),
    toast: document.getElementById("toast")
};

document.addEventListener("DOMContentLoaded", () => {
    bindEvents();
    init().catch((error) => {
        console.error(error);
        showToast(error.message || "console init failed", true);
    });
});

function bindEvents() {
    dom.sessionForm.addEventListener("submit", onCreateSession);
    dom.refreshSessionsButton.addEventListener("click", () => refreshAll(true));
    dom.taskForm.addEventListener("submit", onCreateTask);
    dom.followupButton.addEventListener("click", onFollowupDraft);
    dom.clearFollowupButton.addEventListener("click", onClearFollowup);
    dom.taskTimeline.addEventListener("click", onTimelineClick);
    dom.taskTimeline.addEventListener("keydown", onTimelineKeydown);
    dom.chainContext.addEventListener("click", onChainContextClick);
    dom.taskActions.addEventListener("click", onTaskActionClick);
    dom.refreshTaskButton.addEventListener("click", () => {
        if (state.selectedTaskId) {
            loadSelectedTask(state.selectedTaskId, true).catch(handleError);
        }
    });
    dom.handoffButton.addEventListener("click", onHandoff);
    window.addEventListener("hashchange", () => {
        applyLocationSelection();
        refreshAll(false).catch(handleError);
    });
    document.addEventListener("visibilitychange", () => {
        dom.pollingState.textContent = document.hidden ? "PAUSED" : "ON";
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
    if (state.selectedTaskId) {
        await loadSelectedTask(state.selectedTaskId, false);
    } else if (state.tasks.length > 0) {
        await selectTask(state.tasks[state.tasks.length - 1].id, false);
    } else {
        renderInspector();
    }
    if (loud) {
        showToast("会话和任务已刷新");
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
    dom.heroTitle.textContent = currentSession
        ? `会话：${currentSession.title}`
        : "把任务像对话一样发给 harness";
    dom.heroSubtitle.textContent = currentSession
        ? "左边切会话，中间发新任务，右边观察当前任务如何在 control graph 中推进。"
        : "先创建会话，或直接发布第一个任务让系统自动创建 session。";
    renderSessions();
    renderComposerContext();
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

    dom.taskCount.textContent = String(state.tasks.length);
    const chainCount = buildTaskChains(state.tasks).length;
    dom.timelineHint.textContent = state.selectedSessionId
        ? `当前显示 ${chainCount} 条迭代链 / ${state.tasks.length} 个任务。`
        : "当前未锁定会话，显示最近任务。";
    renderTimeline();
    renderComposerContext();
    syncLocationSelection();
}

async function loadSelectedTask(taskId, loud) {
    state.liveFlow = await api(`/api/v1/tasks/${encodeURIComponent(taskId)}/live_flow?limit=8`);
    state.selectedTaskId = taskId;
    renderTimeline();
    renderInspector();
    renderComposerContext();
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

    const title = dom.taskTitle.value.trim() || deriveTitle(intent);
    const goal = dom.taskGoal.value.trim();
    const followupParentTaskId = state.followupParentTaskId;
    const body = {
        title,
        task_type: dom.taskType.value,
        source: "user",
        priority: dom.taskPriority.value,
        intent,
        goal: goal || null,
        parent_task_id: followupParentTaskId,
        auto_start: dom.taskAutoStart.checked,
        session_id: state.selectedSessionId,
        metadata: {
            source_surface: "web_console",
            created_via: "dialogue_desk",
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

    if (!state.selectedSessionId || task.session_id !== state.selectedSessionId) {
        state.selectedSessionId = task.session_id;
        await loadSessions();
    }

    await loadTasks();
    await selectTask(task.id, false);
    showToast(`任务已发布：${task.title}`);
}

async function onTaskActionClick(event) {
    const button = event.target.closest("[data-task-action]");
    if (!button || !state.selectedTaskId) {
        return;
    }

    const action = button.dataset.taskAction;
    await api(`/api/v1/tasks/${encodeURIComponent(state.selectedTaskId)}/${action}`);
    await loadTasks();
    await loadSelectedTask(state.selectedTaskId, false);
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

function onTimelineClick(event) {
    const card = event.target.closest("[data-task-id]");
    if (!card) {
        return;
    }
    selectTask(card.dataset.taskId, false).catch(handleError);
}

function onTimelineKeydown(event) {
    const thread = event.target.closest("[data-task-id]");
    if (!thread || (event.key !== "Enter" && event.key !== " ")) {
        return;
    }
    event.preventDefault();
    selectTask(thread.dataset.taskId, false).catch(handleError);
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
        dom.sessionList.innerHTML = emptyState("还没有 session。先在下面发布任务，系统会自动创建。");
        return;
    }

    dom.sessionList.innerHTML = state.sessions.map((session) => {
        const active = session.id === state.selectedSessionId ? "is-active" : "";
        const taskCount = state.selectedSessionId === session.id ? state.tasks.length : "";
        return `
            <button class="session-card ${active}" data-session-id="${escapeHtml(session.id)}" type="button">
                <div class="session-card__title">${escapeHtml(session.title || session.id)}</div>
                <div class="session-card__meta">
                    <span class="task-badge" data-tone="${toneForStatus(session.status)}">${escapeHtml(session.status || "active")}</span>
                    <span>${formatTime(session.updated_at || session.updatedAt)}</span>
                    ${taskCount !== "" ? `<span>${taskCount} tasks</span>` : ""}
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
            if (state.tasks.length > 0) {
                await selectTask(state.tasks[state.tasks.length - 1].id, false);
            } else {
                state.selectedTaskId = null;
                state.liveFlow = null;
                renderTimeline();
                renderInspector();
            }
        });
    });
}

function renderTimeline() {
    if (state.tasks.length === 0) {
        dom.taskTimeline.innerHTML = emptyState("当前会话还没有任务。把下面的发布区当作对话输入框来用。");
        return;
    }

    const tasksById = mapById(state.tasks);
    const chains = buildTaskChains(state.tasks);
    dom.taskTimeline.innerHTML = chains.map((chain, chainIndex) => {
        const selectedInChain = chain.tasks.some((task) => task.id === state.selectedTaskId) ? "is-active" : "";
        const rootTask = chain.rootTask || chain.tasks[0];
        const latestTask = chain.latestTask || chain.tasks[chain.tasks.length - 1];
        const latestStartMode = taskStartMode(latestTask);
        return `
            <section class="chain ${selectedInChain}">
                <header class="chain__header">
                    <div>
                        <p class="eyebrow">Iteration Chain ${String(chainIndex + 1).padStart(2, "0")}</p>
                        <h3 class="chain__title">${escapeHtml(rootTask?.title || chain.rootId)}</h3>
                    </div>
                    <div class="chain__meta">
                        <span class="task-badge">${escapeHtml(`${chain.tasks.length} tasks`)}</span>
                        <span class="task-badge" data-tone="${toneForStatus(latestTask?.status)}">${escapeHtml(latestTask?.status || "active")}</span>
                        <span class="task-badge">${escapeHtml(latestTask?.control_node || latestTask?.controlNode || "intake")}</span>
                        ${latestStartMode ? `<span class="task-badge" data-tone="${startModeTone(latestStartMode)}">${escapeHtml(latestStartMode)}</span>` : ""}
                    </div>
                </header>
                <div class="chain__body">
                    ${chain.tasks.map((task, taskIndex) => {
                        const active = task.id === state.selectedTaskId ? "is-active" : "";
                        const liveFlow = task.id === state.selectedTaskId ? state.liveFlow : null;
                        const userMessage = buildUserMessage(task);
                        const assistantMessage = buildAssistantMessage(task, liveFlow);
                        const assistantSignals = buildAssistantSignals(task, liveFlow);
                        const toolCount = liveFlow ? (liveFlow.tool_invocations || liveFlow.toolInvocations || []).length : null;
                        const artifactCount = liveFlow
                            ? ((liveFlow.runtime_context || liveFlow.runtimeContext || {}).recent_artifacts
                                || (liveFlow.runtime_context || liveFlow.runtimeContext || {}).recentArtifacts
                                || []).length
                            : null;
                        const parentTask = taskParent(task, tasksById);
                        const startMode = taskStartMode(task);
                        const roundLabel = taskIndex === 0 ? "root" : `round ${taskIndex + 1}`;
                        return `
                            <article class="thread ${active}" data-task-id="${escapeHtml(task.id)}" role="button" tabindex="0" aria-label="查看任务 ${escapeHtml(task.title || task.id)}">
                                <div class="thread__rail">
                                    <span class="thread__stamp">${escapeHtml(roundLabel)}</span>
                                    <span class="thread__time">${formatTime(task.created_at || task.createdAt)}</span>
                                    <span class="thread__line"></span>
                                </div>
                                <div class="thread__stack">
                                    <div class="message message--user">
                                        <div class="message__meta">
                                            <span class="message__role">Brief</span>
                                            <span>${escapeHtml(task.title || task.id)}</span>
                                            ${task.goal ? `<span>${escapeHtml(preview(task.goal, 84))}</span>` : ""}
                                            ${parentTask ? `<span class="message__parent">follow-up of ${escapeHtml(parentTask.title || parentTask.id)}</span>` : ""}
                                        </div>
                                        <div class="message__body">${escapeHtml(userMessage)}</div>
                                    </div>

                                    <div class="message message--assistant">
                                        <div class="message__meta">
                                            <span class="message__role">Harness</span>
                                            <span class="task-badge" data-tone="${toneForStatus(task.status)}">${escapeHtml(task.status)}</span>
                                            <span class="task-badge">${escapeHtml(task.control_node || task.controlNode || "intake")}</span>
                                            ${startMode ? `<span class="task-badge" data-tone="${startModeTone(startMode)}">${escapeHtml(startMode)}</span>` : ""}
                                            ${task.assigned_worker || task.assignedWorker ? `<span class="task-badge">${escapeHtml(task.assigned_worker || task.assignedWorker)}</span>` : ""}
                                        </div>
                                        <div class="message__body">${escapeHtml(assistantMessage)}</div>
                                        ${assistantSignals.length > 0 ? `
                                            <div class="message__facts">
                                                ${assistantSignals.map((signal) => `<span class="message__fact">${escapeHtml(signal)}</span>`).join("")}
                                            </div>
                                        ` : ""}
                                        <div class="message__foot">
                                            ${task.next_step || task.nextStep ? `<span class="message__hint">Next: ${escapeHtml(preview(task.next_step || task.nextStep, 92))}</span>` : ""}
                                            ${toolCount !== null ? `<span class="message__hint">Tools: ${escapeHtml(String(toolCount))}</span>` : ""}
                                            ${artifactCount !== null ? `<span class="message__hint">Artifacts: ${escapeHtml(String(artifactCount))}</span>` : ""}
                                            <span class="message__hint mono">${escapeHtml(task.id)}</span>
                                        </div>
                                    </div>
                                </div>
                            </article>
                        `;
                    }).join("")}
                </div>
            </section>
        `;
    }).join("");
}

function renderInspector() {
    const flow = state.liveFlow;
    const task = flow?.task || state.tasks.find((item) => item.id === state.selectedTaskId);

    if (!task) {
        dom.inspectorTitle.textContent = "选择一个任务";
        dom.taskOverview.innerHTML = emptyState("右侧会显示 status、control node、worker、tool trace 和连续性摘要。");
        dom.chainContext.innerHTML = emptyState("选中一个任务后，可在这里查看当前迭代链并跳转前后轮。");
        dom.continuitySummary.innerHTML = emptyState("暂无任务详情");
        dom.continuityChips.innerHTML = "";
        dom.routeBox.innerHTML = emptyState("暂无路由信息");
        dom.decisionList.innerHTML = emptyState("暂无 judgment");
        dom.artifactList.innerHTML = emptyState("暂无 artifact");
        dom.toolList.innerHTML = emptyState("暂无 tool trace");
        dom.rawJson.textContent = "";
        setTaskActionState(false);
        renderComposerContext();
        return;
    }

    const activeContext = flow?.runtime_context?.active_context || flow?.runtimeContext?.activeContext || {};
    const routePreview = flow?.route_preview || flow?.routePreview;
    const runtimeContext = flow?.runtime_context || flow?.runtimeContext || {};
    const judgmentTrace = flow?.judgment_trace || flow?.judgmentTrace || {};
    const artifacts = runtimeContext.recent_artifacts || runtimeContext.recentArtifacts || [];
    const decisions = runtimeContext.recent_decisions || runtimeContext.recentDecisions || [];
    const tools = flow?.tool_invocations || flow?.toolInvocations || [];
    const latestPacket = flow?.latest_packet || flow?.latestPacket;

    dom.inspectorTitle.textContent = task.title || task.id;
    dom.taskOverview.innerHTML = [
        overviewCard("状态", task.status),
        overviewCard("控制节点", task.control_node || task.controlNode || "intake"),
        overviewCard("当前 worker", task.assigned_worker || task.assignedWorker || "unassigned"),
        overviewCard("下一步", task.next_step || task.nextStep || latestPacket?.next_step || latestPacket?.nextStep || "none")
    ].join("");
    dom.chainContext.innerHTML = renderChainContext(task);

    const continuitySummary =
        activeContext.continuity_summary ||
        activeContext.continuitySummary ||
        task.summary ||
        latestPacket?.active_task_summary ||
        latestPacket?.activeTaskSummary ||
        "还没有足够的连续性摘要。";

    dom.continuitySummary.textContent = continuitySummary;

    const chips = [
        ...toChipLines("open", activeContext.open_questions || activeContext.openQuestions),
        ...toChipLines("next", activeContext.next_candidates || activeContext.nextCandidates),
        ...toChipLines("risk", activeContext.risk_hints || activeContext.riskHints),
        ...toChipLines("learned", activeContext.learned_hints || activeContext.learnedHints)
    ];
    dom.continuityChips.innerHTML = chips.length > 0 ? chips.map((chip) => `<span class="chip">${escapeHtml(chip)}</span>`).join("") : emptyState("当前 active context 没有额外 hint。");

    dom.routeBox.innerHTML = routePreview ? `
        <div class="route-box">
            <div class="artifact-item__meta">
                <span class="task-badge">${escapeHtml(routePreview.selected_worker || routePreview.selectedWorker || "unassigned")}</span>
                <span>${escapeHtml(routePreview.route_source || routePreview.routeSource || "router")}</span>
                <span>${escapeHtml(routePreview.task_type || routePreview.taskType || "general")}</span>
            </div>
            <p>${escapeHtml(routePreview.route_reason || routePreview.routeReason || "")}</p>
            <p class="mono">${escapeHtml((routePreview.candidate_workers || routePreview.candidateWorkers || []).join(", "))}</p>
        </div>
    ` : emptyState("暂无 route preview");

    const executionJudgment = judgmentTrace.execution_judgment || judgmentTrace.executionJudgment;
    const completionJudgment = judgmentTrace.completion_judgment || judgmentTrace.completionJudgment;
    const decisionCards = [];
    if (executionJudgment) {
        decisionCards.push(decisionCard("execution_judgment", executionJudgment));
    }
    if (completionJudgment) {
        decisionCards.push(decisionCard("completion_judgment", completionJudgment));
    }
    decisions.slice(0, 4).forEach((decision) => {
        decisionCards.push(decisionCard(decision.decision_type || decision.decisionType || "decision", decision));
    });
    dom.decisionList.innerHTML = decisionCards.length > 0 ? decisionCards.join("") : emptyState("暂无 decision");

    dom.artifactList.innerHTML = artifacts.length > 0
        ? artifacts.map((artifact) => `
            <div class="artifact-item">
                <div class="artifact-item__meta">
                    <span class="task-badge">${escapeHtml(artifact.artifact_type || artifact.artifactType || "artifact")}</span>
                    <span>${formatTime(artifact.created_at || artifact.createdAt)}</span>
                </div>
                <strong>${escapeHtml(artifact.title || "untitled artifact")}</strong>
                <p>${escapeHtml(preview(artifact.summary || artifact.uri || "", 220))}</p>
            </div>
        `).join("")
        : emptyState("暂无 artifact");

    dom.toolList.innerHTML = tools.length > 0
        ? tools.map((tool) => `
            <div class="tool-item">
                <div class="tool-item__meta">
                    <span class="task-badge" data-tone="${tool.success ? "active" : "failed"}">${tool.success ? "success" : "failed"}</span>
                    <span class="task-badge">${escapeHtml(tool.tool_name || tool.toolName)}</span>
                    <span>${formatTime(tool.created_at || tool.createdAt)}</span>
                    ${tool.elapsed_ms || tool.elapsedMs ? `<span>${escapeHtml(String(tool.elapsed_ms || tool.elapsedMs))} ms</span>` : ""}
                </div>
                <p>${escapeHtml(preview(tool.result_summary || tool.resultSummary || "", 220))}</p>
            </div>
        `).join("")
        : emptyState("当前任务还没有 tool trace");

    dom.rawJson.textContent = JSON.stringify(flow, null, 2);
    setTaskActionState(true);
    renderComposerContext();
}

function renderWorkerOptions() {
    const options = state.workers.map((worker) => `
        <option value="${escapeHtml(worker.worker_id || worker.workerId)}">${escapeHtml(worker.worker_id || worker.workerId)}</option>
    `);
    dom.handoffWorker.innerHTML = options.join("");
    dom.handoffButton.disabled = options.length === 0;
}

function overviewCard(label, value) {
    return `
        <div class="overview-card">
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(value || "n/a")}</strong>
        </div>
    `;
}

function decisionCard(type, decision) {
    return `
        <div class="decision-item">
            <div class="decision-item__type">${escapeHtml(type)}</div>
            <strong>${escapeHtml(decision.summary || "no summary")}</strong>
            ${decision.rationale ? `<p>${escapeHtml(preview(decision.rationale, 220))}</p>` : ""}
        </div>
    `;
}

function buildUserMessage(task) {
    const intent = task.metadata?.intent || task.goal || task.title || task.id;
    return preview(intent, 260);
}

function buildAssistantMessage(task, flow) {
    const activeContext = flow?.runtime_context?.active_context || flow?.runtimeContext?.activeContext || {};
    const continuitySummary = activeContext.continuity_summary || activeContext.continuitySummary;
    const executionJudgment = flow?.judgment_trace?.execution_judgment || flow?.judgmentTrace?.executionJudgment;
    const completionJudgment = flow?.judgment_trace?.completion_judgment || flow?.judgmentTrace?.completionJudgment;

    return preview(
        continuitySummary
        || task.summary
        || latestOutput(flow)
        || completionJudgment?.summary
        || executionJudgment?.summary
        || task.next_step
        || task.nextStep
        || "任务已进入 harness，等待继续推进。",
        260
    );
}

function buildAssistantSignals(task, flow) {
    const judgmentTrace = flow?.judgment_trace || flow?.judgmentTrace || {};
    const routePreview = flow?.route_preview || flow?.routePreview || {};
    const latestPacket = flow?.latest_packet || flow?.latestPacket || {};
    const executionJudgment = judgmentTrace.execution_judgment || judgmentTrace.executionJudgment || {};
    const completionJudgment = judgmentTrace.completion_judgment || judgmentTrace.completionJudgment || {};
    const signals = [
        valueLine("action", judgmentTrace.recommended_action || judgmentTrace.recommendedAction || executionJudgment.metadata?.action),
        valueLine("completion", completionJudgment.metadata?.completion_status || completionJudgment.metadata?.status),
        valueLine("route", routePreview.selected_worker || routePreview.selectedWorker),
        valueLine("packet", latestPacket.active_task_summary || latestPacket.activeTaskSummary)
    ].filter(Boolean);
    return signals.slice(0, 4);
}

function buildFollowupDraft(task, flow) {
    const judgmentTrace = flow?.judgment_trace || flow?.judgmentTrace || {};
    const recommendedNextStep = judgmentTrace.recommended_next_step || judgmentTrace.recommendedNextStep;
    const nextStep = firstNonBlank(recommendedNextStep, task.next_step, task.nextStep, task.summary);
    const latest = firstNonBlank(buildAssistantMessage(task, flow), task.summary, latestOutput(flow));
    const taskType = task.metadata?.task_type || dom.taskType.value || "continuation";
    const priority = task.priority || dom.taskPriority.value || "high";
    const goal = firstNonBlank(task.goal, `继续推进 ${task.title || task.id}`);
    const title = deriveTitle(`跟进：${task.title || task.id}`);
    const intentLines = [
        `基于当前任务继续推进：${task.title || task.id}。`,
        latest ? `当前进展：${preview(latest, 180)}` : null,
        nextStep ? `优先处理：${preview(nextStep, 180)}` : "请先判断下一步，再继续推进。",
        "保持和上一轮产物一致，不要重复从零开始。"
    ].filter(Boolean);

    return {
        title,
        taskType,
        priority,
        goal,
        intent: intentLines.join("\n")
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
    const status = firstNonBlank(contextTask.status, "active");
    const node = firstNonBlank(contextTask.control_node, contextTask.controlNode, "intake");
    const selectedLine = task
        ? `当前选中：${task.title || task.id} · ${firstNonBlank(task.status, "active")}/${firstNonBlank(task.control_node, task.controlNode, "intake")}`
        : null;
    const followupLine = followupParent
        ? `已关联迭代链：${followupParent.title || followupParent.id} · 新任务会挂到这条链上`
        : "尚未绑定 follow-up 父任务。";
    const nextLine = nextStep ? `参考下一步：${preview(nextStep, 96)}` : null;
    dom.composerTaskHint.textContent = [selectedLine, followupLine, !followupParent ? `可基于 ${contextTask.title || contextTask.id} 生成 follow-up。` : null, nextLine, followupParent ? `当前上下文：${status}/${node}` : null]
        .filter(Boolean)
        .join(" · ");
    dom.followupButton.disabled = !task;
    dom.clearFollowupButton.disabled = !followupParent;
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
                <div class="chain-context__badges">
                    <span class="task-badge">${escapeHtml(`${chain.tasks.length} tasks`)}</span>
                    <span class="task-badge">${escapeHtml(currentRound)}</span>
                    <span class="task-badge" data-tone="${toneForStatus(latestTask?.status)}">${escapeHtml(latestTask?.status || "active")}</span>
                </div>
            </div>
            <div class="chain-context__nav">
                <button class="button button--ghost chain-context__jump" type="button" ${previousTask ? `data-chain-task-id="${escapeHtml(previousTask.id)}"` : "disabled"}>
                    ${previousTask ? `上一轮 · ${escapeHtml(previousTask.title || previousTask.id)}` : "已经是首轮"}
                </button>
                <button class="button button--ghost chain-context__jump" type="button" ${nextTask ? `data-chain-task-id="${escapeHtml(nextTask.id)}"` : "disabled"}>
                    ${nextTask ? `下一轮 · ${escapeHtml(nextTask.title || nextTask.id)}` : "已经是最新一轮"}
                </button>
            </div>
            <div class="chain-context__list">
                ${chain.tasks.map((item, index) => {
                    const active = item.id === task.id ? "is-active" : "";
                    const startMode = taskStartMode(item);
                    const roundLabel = index === 0 ? "root" : `round ${index + 1}`;
                    return `
                        <button class="chain-context__task ${active}" type="button" data-chain-task-id="${escapeHtml(item.id)}">
                            <div class="chain-context__task-head">
                                <span class="task-badge">${escapeHtml(roundLabel)}</span>
                                <span class="task-badge" data-tone="${toneForStatus(item.status)}">${escapeHtml(item.status || "active")}</span>
                                ${startMode ? `<span class="task-badge" data-tone="${startModeTone(startMode)}">${escapeHtml(startMode)}</span>` : ""}
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
    const explicitMode = firstNonBlank(
        task?.metadata?.start_mode,
        task?.metadata?.startMode
    );
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
    dom.toast.style.background = isError ? "rgba(118, 39, 25, 0.94)" : "rgba(18, 32, 45, 0.92)";
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
    dom.refreshTaskButton.disabled = !enabled;
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
            if (state.selectedTaskId) {
                await loadSelectedTask(state.selectedTaskId, false);
            }
        } catch (error) {
            console.error(error);
        }
    }, 5000);
}
