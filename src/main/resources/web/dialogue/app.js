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
    chainCount: document.getElementById("chainCount"),
    selectedStatus: document.getElementById("selectedStatus"),
    sessionList: document.getElementById("sessionList"),
    sessionForm: document.getElementById("sessionForm"),
    sessionTitle: document.getElementById("sessionTitle"),
    refreshSessionsButton: document.getElementById("refreshSessionsButton"),
    heroTitle: document.getElementById("heroTitle"),
    heroSubtitle: document.getElementById("heroSubtitle"),
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
    decisionList: document.getElementById("decisionList"),
    artifactList: document.getElementById("artifactList"),
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
    if (state.selectedTaskId) {
        await loadSelectedTask(state.selectedTaskId, false);
    } else if (state.tasks.length > 0) {
        await selectTask(state.tasks[state.tasks.length - 1].id, false);
    } else {
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
    dom.heroTitle.textContent = currentSession
        ? `会话：${currentSession.title}`
        : "把 task 当作对话发给 harness";
    dom.heroSubtitle.textContent = currentSession
        ? "这里更像 chat 工作台，但底层仍然是 task、control graph 和 live flow。"
        : "先创建会话，或直接发布第一条任务让系统自动创建 session。";
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

    const chains = buildTaskChains(state.tasks);
    dom.taskCount.textContent = String(state.tasks.length);
    dom.chainCount.textContent = String(chains.length);
    dom.threadHint.textContent = state.selectedSessionId
        ? `当前展示 ${chains.length} 条迭代链 / ${state.tasks.length} 个任务。`
        : "当前未锁定 session，展示最近任务。";
    renderThread();
    renderComposerContext();
    syncLocationSelection();
}

async function loadSelectedTask(taskId, loud) {
    state.liveFlow = await api(`/api/v1/tasks/${encodeURIComponent(taskId)}/live_flow?limit=8`);
    state.selectedTaskId = taskId;
    renderThread();
    renderDetails();
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

    const followupParentTaskId = state.followupParentTaskId;
    const body = {
        title: dom.taskTitle.value.trim() || deriveTitle(intent),
        task_type: dom.taskType.value,
        source: "user",
        priority: dom.taskPriority.value,
        intent,
        goal: dom.taskGoal.value.trim() || null,
        parent_task_id: followupParentTaskId,
        auto_start: dom.taskAutoStart.checked,
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
            if (state.tasks.length > 0) {
                await selectTask(state.tasks[state.tasks.length - 1].id, false);
            } else {
                state.selectedTaskId = null;
                state.liveFlow = null;
                renderThread();
                renderDetails();
            }
        });
    });
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
        dom.continuitySummary.innerHTML = "选中一个任务后，这里会显示 active context 和 continuity 摘要。";
        dom.continuityChips.innerHTML = "";
        dom.routeBox.innerHTML = emptyState("暂无 route preview");
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
    const routePreview = flow?.route_preview || flow?.routePreview || {};
    const judgmentTrace = flow?.judgment_trace || flow?.judgmentTrace || {};
    const decisions = flow?.decisions || [];
    const recentArtifacts = (flow?.runtime_context?.recent_artifacts || flow?.runtimeContext?.recentArtifacts || []).slice(0, 5);
    const tools = (flow?.tool_invocations || flow?.toolInvocations || []).slice(0, 6);

    dom.detailTitle.textContent = task.title || task.id;
    dom.selectedStatus.textContent = `${task.status || "active"} / ${task.control_node || task.controlNode || "intake"}`;
    dom.taskOverview.innerHTML = [
        overviewCard("任务 ID", task.id),
        overviewCard("状态", task.status || "active"),
        overviewCard("控制节点", task.control_node || task.controlNode || "intake"),
        overviewCard("Worker", task.assigned_worker || task.assignedWorker || "unassigned")
    ].join("");

    dom.chainContext.innerHTML = renderChainContext(task);
    dom.continuitySummary.textContent = continuitySummary || "暂无 continuity summary";
    dom.continuityChips.innerHTML = [
        ...toChipLines("open", openQuestions),
        ...toChipLines("next", nextCandidates)
    ].map((line) => `<span class="chip">${escapeHtml(line)}</span>`).join("");

    dom.routeBox.innerHTML = `
        <div class="stack-item">
            <div class="stack-item__meta">
                <span class="task-badge">${escapeHtml(routePreview.selected_worker || routePreview.selectedWorker || "pending")}</span>
                <span>${escapeHtml(routePreview.reason || routePreview.summary || "等待 route preview")}</span>
            </div>
            <strong>${escapeHtml(preview(routePreview.rationale || routePreview.reason || "当前没有完整 route preview。", 220))}</strong>
        </div>
    `;

    const decisionCards = [];
    const executionJudgment = judgmentTrace.execution_judgment || judgmentTrace.executionJudgment;
    const completionJudgment = judgmentTrace.completion_judgment || judgmentTrace.completionJudgment;
    if (executionJudgment) {
        decisionCards.push(decisionCard("execution", executionJudgment));
    }
    if (completionJudgment) {
        decisionCards.push(decisionCard("completion", completionJudgment));
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

    dom.toolList.innerHTML = tools.length > 0
        ? tools.map((tool) => stackItem(
            tool.tool_name || tool.toolName || "tool",
            tool.success ? "success" : "failed",
            preview(tool.result_summary || tool.resultSummary || "", 220),
            `${formatTime(tool.created_at || tool.createdAt)}${tool.elapsed_ms || tool.elapsedMs ? ` · ${tool.elapsed_ms || tool.elapsedMs} ms` : ""}`
        )).join("")
        : emptyState("当前任务还没有 tool trace");

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

function overviewCard(label, value) {
    return `
        <div class="overview-card">
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(value || "n/a")}</strong>
        </div>
    `;
}

function decisionCard(type, decision) {
    return stackItem(
        type,
        decision.summary || "no summary",
        preview(decision.rationale || decision.reason || "", 220),
        decision.created_at || decision.createdAt ? formatTime(decision.created_at || decision.createdAt) : ""
    );
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
        valueLine("route", routePreview.selected_worker || routePreview.selectedWorker),
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
            if (state.selectedTaskId) {
                await loadSelectedTask(state.selectedTaskId, false);
            }
        } catch (error) {
            console.error(error);
        }
    }, 5000);
}
