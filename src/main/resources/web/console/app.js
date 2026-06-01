import { buildRecoveryJobPlan } from "../dialogue/recovery-job-plan.js";

import { buildToolTraceStatusLabel, buildToolTraceSummary } from "../dialogue/tool-trace-plan.js";
import { buildProviderRunFilePlan } from "../dialogue/provider-run-file-plan.js";
import { buildAgentActionPlan } from "../dialogue/agent-action-plan.js";
import { buildExecutionSurfaceSummaryPlan } from "../dialogue/execution-surface-summary-plan.js";

const state = {
    sessions: [],
    tasks: [],
    workers: [],
    agents: [],
    runtimeHealth: null,
    agentRunSearchFilters: {
        providerId: "",
        status: "",
        role: "",
        taskId: "",
        limit: "10"
    },
    agentRunSearchResults: [],
    selectedAgentId: null,
    selectedAgent: null,
    selectedWorkerReadiness: null,
    selectedAgentRuns: [],
    selectedAgentRunId: null,
    selectedAgentRun: null,
    selectedAgentRunEvents: [],
    selectedAgentRunArtifacts: [],
    selectedSessionId: null,
    selectedTaskId: null,
    followupParentTaskId: null,
    liveFlow: null,
    recoveryJobs: [],
    providerSelection: null,
    agentRun: null,
    agentRunEvents: [],
    agentRunArtifacts: [],
    agentActions: [],
    experimentSummary: null,
    toastTimer: null,
    pollingTimer: null
};

const dom = {
    healthBadge: document.getElementById("healthBadge"),
    sessionCount: document.getElementById("sessionCount"),
    taskCount: document.getElementById("taskCount"),
    agentCount: document.getElementById("agentCount"),
    readyAgentCount: document.getElementById("readyAgentCount"),
    activeRunCount: document.getElementById("activeRunCount"),
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
    refreshAgentsButton: document.getElementById("refreshAgentsButton"),
    refreshRuntimeButton: document.getElementById("refreshRuntimeButton"),
    refreshRunSearchButton: document.getElementById("refreshRunSearchButton"),
    agentInventory: document.getElementById("agentInventory"),
    agentDetail: document.getElementById("agentDetail"),
    runtimeHealth: document.getElementById("runtimeHealth"),
    agentRunSearch: document.getElementById("agentRunSearch"),
    agentExecution: document.getElementById("agentExecution"),
    agentRunDetail: document.getElementById("agentRunDetail"),
    chainContext: document.getElementById("chainContext"),
    continuitySummary: document.getElementById("continuitySummary"),
    continuityChips: document.getElementById("continuityChips"),
    mountedContext: document.getElementById("mountedContext"),
    routeBox: document.getElementById("routeBox"),
    experimentSummary: document.getElementById("experimentSummary"),
    decisionList: document.getElementById("decisionList"),
    artifactList: document.getElementById("artifactList"),
    agentActionList: document.getElementById("agentActionList"),
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
    dom.refreshAgentsButton.addEventListener("click", () => loadAgents(true).catch(handleError));
    dom.refreshRuntimeButton.addEventListener("click", () => loadRuntimeHealth(true).catch(handleError));
    dom.refreshRunSearchButton.addEventListener("click", () => loadAgentRunSearch(true).catch(handleError));
    dom.agentInventory.addEventListener("click", onAgentInventoryClick);
    dom.agentDetail.addEventListener("click", onAgentDetailClick);
    dom.runtimeHealth.addEventListener("click", onRuntimeHealthClick);
    dom.agentRunSearch.addEventListener("submit", onAgentRunSearchSubmit);
    dom.agentRunSearch.addEventListener("click", onAgentRunSearchClick);
    dom.agentExecution.addEventListener("click", onAgentExecutionClick);
    dom.taskTimeline.addEventListener("click", onTimelineClick);
    dom.taskTimeline.addEventListener("keydown", onTimelineKeydown);
    dom.chainContext.addEventListener("click", onChainContextClick);
    dom.taskActions.addEventListener("click", onTaskActionClick);
    dom.toolList.addEventListener("click", (event) => {
        onProviderRunFileClick(event).catch(handleError);
    });
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
    await Promise.all([loadHealth(), loadWorkers(), loadAgents(false), loadRuntimeHealth(false), loadAgentRunSearch(false)]);
    await refreshAll(false);
    startPolling();
}

async function refreshAll(loud) {
    await loadSessions();
    await loadTasks();
    await loadRuntimeHealth(false);
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
    dom.healthBadge.textContent = health.status === "up" ? `healthy 路 v${health.version}` : "down";
}

async function loadWorkers() {
    state.workers = await api("/api/v1/workers");
    renderWorkerOptions();
}

async function loadAgents(loud) {
    state.agents = await api("/api/v1/agents");
    if (state.selectedAgentId && !state.agents.some((agent) => providerIdOf(agent) === state.selectedAgentId)) {
        state.selectedAgentId = null;
        state.selectedAgent = null;
        state.selectedWorkerReadiness = null;
        state.selectedAgentRuns = [];
    }
    renderAgentInventory();
    renderAgentDetail();
    if (loud) {
        showToast("Agent inventory 已刷新");
    }
}

async function loadRuntimeHealth(loud) {
    state.runtimeHealth = await api("/api/v1/runtime_health?limit=8");
    renderRuntimeHealth();
    if (loud) {
        showToast("Runtime health 已刷新");
    }
}

async function loadAgentDetail(providerId, loud = false) {
    const encodedProviderId = encodeURIComponent(providerId);
    const workerId = workerIdForProvider(providerId);
    const [agent, runs, workerReadiness] = await Promise.all([
        api(`/api/v1/agents/${encodedProviderId}`),
        apiOrNull(`/api/v1/agents/${encodedProviderId}/runs?limit=20`),
        workerId ? apiOrNull(`/api/v1/workers/${encodeURIComponent(workerId)}/readiness?mode=dispatch`) : Promise.resolve(null)
    ]);
    state.selectedAgentId = providerId;
    state.selectedAgent = agent;
    state.selectedWorkerReadiness = workerReadiness;
    state.selectedAgentRuns = runs || [];
    renderAgentInventory();
    renderAgentDetail();
    if (loud) {
        showToast(`已加载 Provider ${providerId}`);
    }
}

async function refreshAgent(providerId) {
    const encodedProviderId = encodeURIComponent(providerId);
    const agent = await api(`/api/v1/agents/${encodedProviderId}/refresh`, {
        method: "POST",
        body: "{}"
    });
    state.selectedAgentId = providerId;
    state.selectedAgent = agent;
    await loadAgents(false);
    await loadAgentDetail(providerId, false);
    showToast(`Provider ${providerId} 状态已刷新`);
}

async function loadAgentRunDetail(runId, loud = false) {
    const encodedRunId = encodeURIComponent(runId);
    const [run, events, artifacts] = await Promise.all([
        api(`/api/v1/agent_runs/${encodedRunId}`),
        apiOrNull(`/api/v1/agent_runs/${encodedRunId}/events?limit=20`),
        apiOrNull(`/api/v1/agent_runs/${encodedRunId}/artifacts?limit=20`)
    ]);
    state.selectedAgentRunId = runId;
    state.selectedAgentRun = run;
    state.selectedAgentRunEvents = events || [];
    state.selectedAgentRunArtifacts = artifacts || [];
    renderAgentRunDetail();
    if (loud) {
        showToast(`已加载 Run ${runId}`);
    }
}

async function loadAgentRunSearch(loud = false) {
    const filters = state.agentRunSearchFilters || {};
    const params = new URLSearchParams();
    appendQueryParam(params, "provider_id", filters.providerId);
    appendQueryParam(params, "status", filters.status);
    appendQueryParam(params, "role", filters.role);
    appendQueryParam(params, "task_id", filters.taskId);
    appendQueryParam(params, "limit", filters.limit || "10");
    state.agentRunSearchResults = await api(`/api/v1/agent_runs?${params.toString()}`);
    renderAgentRunSearch();
    if (loud) {
        showToast("Agent run search 已刷新");
    }
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
        ? `会话：${currentSession.title}`
        : "把任务像对话一样发给 harness";
    dom.heroSubtitle.textContent = currentSession
        ? "左边切会话，中间发新任务，右边观察当前任务如何在 control graph 中推进。"
        : "先创建会话，或直接发布第一个任务让系统自动创建 session。";
    renderSessions();
    renderComposerContext();
}

async function loadTasks() {
    const previousSelectedTaskId = state.selectedTaskId;
    if (state.selectedSessionId) {
        state.tasks = await api(`/api/v1/sessions/${encodeURIComponent(state.selectedSessionId)}/tasks`);
    } else {
        state.tasks = await api("/api/v1/tasks");
    }

    state.tasks = state.tasks
        .slice()
        .sort((a, b) => timestampMs(a.created_at || a.createdAt || 0) - timestampMs(b.created_at || b.createdAt || 0));

    if (state.selectedTaskId && !state.tasks.some((task) => task.id === state.selectedTaskId)) {
        state.selectedTaskId = state.tasks[state.tasks.length - 1]?.id ?? null;
    }
    if (state.selectedTaskId !== previousSelectedTaskId) {
        state.selectedAgentRunId = null;
        state.selectedAgentRun = null;
        state.selectedAgentRunEvents = [];
        state.selectedAgentRunArtifacts = [];
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
    const encodedTaskId = encodeURIComponent(taskId);
    const flow = await api(`/api/v1/tasks/${encodedTaskId}/live_flow?limit=8`);
    const providerSelection = flow.provider_selection || flow.providerSelection
        || await apiOrNull(`/api/v1/tasks/${encodedTaskId}/provider_selection`);
    const agentRun = flow.agent_run || flow.agentRun
        || await apiOrNull(`/api/v1/tasks/${encodedTaskId}/agent_run`);
    const flowEvents = flow.agent_run_events || flow.agentRunEvents;
    const flowArtifacts = flow.agent_artifacts || flow.agentArtifacts;
    const runId = agentRun?.run_id || agentRun?.runId;
    const [agentRunEvents, agentRunArtifacts] = await Promise.all([
        flowEvents ? Promise.resolve(flowEvents) : runId
            ? apiOrNull(`/api/v1/agent_runs/${encodeURIComponent(runId)}/events?limit=6`)
            : Promise.resolve([]),
        flowArtifacts ? Promise.resolve(flowArtifacts) : runId
            ? apiOrNull(`/api/v1/agent_runs/${encodeURIComponent(runId)}/artifacts?limit=6`)
            : Promise.resolve([])
    ]);
    const [recoveryJobs, agentActions] = await Promise.all([
        apiOrNull(`/api/v1/tasks/${encodedTaskId}/recovery_jobs?limit=5`),
        apiOrNull(`/api/v1/agent_actions?task_id=${encodedTaskId}&limit=20`)
    ]);
    state.liveFlow = flow;
    state.recoveryJobs = Array.isArray(recoveryJobs) ? recoveryJobs : [];
    state.agentActions = Array.isArray(agentActions) ? agentActions : [];
    state.providerSelection = providerSelection;
    state.agentRun = agentRun;
    state.agentRunEvents = agentRunEvents || [];
    state.agentRunArtifacts = agentRunArtifacts || [];
    if (agentRun && (!state.selectedAgentRunId || state.selectedAgentRunId === runId)) {
        state.selectedAgentRunId = runId || null;
        state.selectedAgentRun = agentRun;
        state.selectedAgentRunEvents = state.agentRunEvents;
        state.selectedAgentRunArtifacts = state.agentRunArtifacts;
    }
    state.experimentSummary = await loadTaskExperimentSummary(taskId, flow);
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
    if (taskId !== state.selectedTaskId) {
        state.selectedAgentRunId = null;
        state.selectedAgentRun = null;
        state.selectedAgentRunEvents = [];
        state.selectedAgentRunArtifacts = [];
    }
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
    const body = action === "recover"
        ? JSON.stringify({ mode: "auto", reason: "manual recovery from console" })
        : "{}";
    const actionPath = action === "recover" ? "recover?async=true" : action;
    const result = await api(`/api/v1/tasks/${encodeURIComponent(state.selectedTaskId)}/${actionPath}`, {
        method: "POST",
        body
    });
    await loadTasks();
    await loadSelectedTask(state.selectedTaskId, false);
    const requestId = result?.request_id || result?.requestId;
    showToast(requestId ? `已触发 ${action}: ${requestId}` : `已执行 ${action}`);
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

function onAgentInventoryClick(event) {
    const action = event.target.closest("[data-provider-action]");
    if (action) {
        const providerId = action.dataset.providerId;
        if (!providerId) {
            return;
        }
        const providerAction = action.dataset.providerAction;
        if (providerAction === "refresh") {
            refreshAgent(providerId).catch(handleError);
        } else if (providerAction === "view_runs") {
            filterAgentRunSearchByProvider(providerId);
        } else if (providerAction === "copy_diagnostics") {
            copyProviderDiagnostics(providerId).catch(handleError);
        } else {
            loadAgentDetail(providerId, true).catch(handleError);
        }
        return;
    }

    const card = event.target.closest("[data-provider-id]");
    if (card?.dataset.providerId) {
        loadAgentDetail(card.dataset.providerId, false).catch(handleError);
    }
}

function onAgentDetailClick(event) {
    const action = event.target.closest("[data-provider-action]");
    if (action?.dataset.providerId) {
        if (action.dataset.providerAction === "refresh") {
            refreshAgent(action.dataset.providerId).catch(handleError);
        } else if (action.dataset.providerAction === "view_runs") {
            filterAgentRunSearchByProvider(action.dataset.providerId);
        } else if (action.dataset.providerAction === "copy_diagnostics") {
            copyProviderDiagnostics(action.dataset.providerId).catch(handleError);
        } else {
            loadAgentDetail(action.dataset.providerId, true).catch(handleError);
        }
        return;
    }

    const runTarget = event.target.closest("[data-run-id]");
    if (runTarget?.dataset.runId) {
        loadAgentRunDetail(runTarget.dataset.runId, true).catch(handleError);
    }
}

function onRuntimeHealthClick(event) {
    const runTarget = event.target.closest("[data-run-id]");
    if (runTarget?.dataset.runId) {
        loadAgentRunDetail(runTarget.dataset.runId, true).catch(handleError);
        return;
    }

    const providerTarget = event.target.closest("[data-provider-id]");
    if (providerTarget?.dataset.providerId) {
        loadAgentDetail(providerTarget.dataset.providerId, true).catch(handleError);
    }
}

function onAgentExecutionClick(event) {
    const runTarget = event.target.closest("[data-run-id]");
    if (runTarget?.dataset.runId) {
        loadAgentRunDetail(runTarget.dataset.runId, true).catch(handleError);
        return;
    }

    const providerTarget = event.target.closest("[data-provider-id]");
    if (providerTarget?.dataset.providerId) {
        loadAgentDetail(providerTarget.dataset.providerId, true).catch(handleError);
    }
}

function onAgentRunSearchSubmit(event) {
    const form = event.target.closest("[data-agent-run-search-form]");
    if (!form) {
        return;
    }
    event.preventDefault();
    const formData = new FormData(form);
    state.agentRunSearchFilters = {
        providerId: String(formData.get("providerId") || "").trim(),
        status: String(formData.get("status") || "").trim(),
        role: String(formData.get("role") || "").trim(),
        taskId: String(formData.get("taskId") || "").trim(),
        limit: String(formData.get("limit") || "10").trim() || "10"
    };
    loadAgentRunSearch(true).catch(handleError);
}

function onAgentRunSearchClick(event) {
    const action = event.target.closest("[data-run-search-action]");
    if (action?.dataset.runSearchAction === "reset") {
        state.agentRunSearchFilters = {
            providerId: "",
            status: "",
            role: "",
            taskId: "",
            limit: "10"
        };
        loadAgentRunSearch(true).catch(handleError);
        return;
    }

    const runTarget = event.target.closest("[data-run-id]");
    if (runTarget?.dataset.runId) {
        loadAgentRunDetail(runTarget.dataset.runId, true).catch(handleError);
    }
}

function filterAgentRunSearchByProvider(providerId) {
    state.agentRunSearchFilters = {
        providerId,
        status: "",
        role: "",
        taskId: "",
        limit: "20"
    };
    loadAgentRunSearch(true).catch(handleError);
}

async function copyProviderDiagnostics(providerId) {
    const encodedProviderId = encodeURIComponent(providerId);
    const agent = state.selectedAgentId === providerId && state.selectedAgent
        ? state.selectedAgent
        : await api(`/api/v1/agents/${encodedProviderId}`);
    const runs = state.selectedAgentId === providerId && state.selectedAgentRuns.length > 0
        ? state.selectedAgentRuns
        : await apiOrNull(`/api/v1/agents/${encodedProviderId}/runs?limit=20`);
    const diagnostics = {
        copied_at: new Date().toISOString(),
        provider: agent,
        recent_runs: runs || [],
        runtime_health: state.runtimeHealth || null
    };
    await writeClipboard(JSON.stringify(diagnostics, null, 2));
    showToast(`Provider ${providerId} diagnostics 已复制`);
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
                            <article class="thread ${active}" data-task-id="${escapeHtml(task.id)}" role="button" tabindex="0" aria-label="当前任务： ${escapeHtml(task.title || task.id)}">
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
        dom.inspectorTitle.textContent = "当前任务";
        dom.taskOverview.innerHTML = emptyState("当前任务没有状态、控制节点、工作节点、工具 trace 等信息。");
        dom.agentExecution.innerHTML = emptyState("当前任务没有执行信息。");
        dom.chainContext.innerHTML = emptyState("当前任务没有上下文信息。");
        dom.continuitySummary.innerHTML = emptyState("当前任务没有连续性信息。");
        dom.continuityChips.innerHTML = "";
        dom.mountedContext.innerHTML = emptyState("当前任务没有 mounted context。");
        dom.routeBox.innerHTML = emptyState("当前任务没有路由信息");
        dom.experimentSummary.innerHTML = emptyState("当前任务没有实验信息");
        dom.decisionList.innerHTML = emptyState("当前任务没有判断 judgment");
        dom.artifactList.innerHTML = emptyState("当前任务没有实验 artifact");
        dom.agentActionList.innerHTML = emptyState("当前任务没有 reconciled action");
        dom.toolList.innerHTML = emptyState("当前任务没有实验 tool trace");
        dom.rawJson.textContent = "";
        state.providerSelection = null;
        state.recoveryJobs = [];
        state.agentRun = null;
        state.agentRunEvents = [];
        state.agentRunArtifacts = [];
        state.agentActions = [];
        renderAgentInventory();
        renderAgentDetail();
        renderRuntimeHealth();
        renderAgentRunDetail();
        setTaskActionState(false);
        renderComposerContext();
        return;
    }

    const activeContext = flow?.runtime_context?.active_context || flow?.runtimeContext?.activeContext || {};
    const routePreview = flow?.route_preview || flow?.routePreview;
    const runtimeContext = flow?.runtime_context || flow?.runtimeContext || {};
    const judgmentTrace = flow?.judgment_trace || flow?.judgmentTrace || {};
    const experimentRun = experimentRunView(flow);
    const artifacts = runtimeContext.recent_artifacts || runtimeContext.recentArtifacts || [];
    const decisions = runtimeContext.recent_decisions || runtimeContext.recentDecisions || [];
    const tools = flow?.tool_invocations || flow?.toolInvocations || [];
    const latestPacket = flow?.latest_packet || flow?.latestPacket;
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
    const executionFacts = executionBoundaryFacts(flow, tools);
    const recoveryJobPlan = buildRecoveryJobPlan(state.recoveryJobs, { formatTime });

    dom.inspectorTitle.textContent = task.title || task.id;
    dom.taskOverview.innerHTML = [
        overviewCard("状态", task.status),
        overviewCard("控制节点", task.control_node || task.controlNode || "intake"),    
        overviewCard("工作节点", task.worker || task.worker || "intake"),
        overviewCard("工具 trace", task.assigned_worker || task.assignedWorker || "unassigned"),
        overviewCard("实验模式", humanizeToken(experimentMode) || experimentMode),
        overviewCard("下一步", task.next_step || task.nextStep || latestPacket?.next_step || latestPacket?.nextStep || "none"),
        overviewCard("Tool chain", toolLabel || "none"),
        overviewCard("Execution", executionFacts.label || "none"),
        renderRecoveryJobPanel(recoveryJobPlan)
    ].join("");
    dom.agentExecution.innerHTML = renderAgentExecution(flow, task);
    renderAgentDetail();
    renderAgentRunDetail();
    dom.chainContext.innerHTML = renderChainContext(task);

    const continuitySummary =
        activeContext.continuity_summary ||
        activeContext.continuitySummary ||
        task.summary ||
        latestPacket?.active_task_summary ||
        "当前任务没有延续摘要。";

    dom.continuitySummary.textContent = continuitySummary;

    const chips = [
        ...toChipLines("open", activeContext.open_questions || activeContext.openQuestions),
        ...toChipLines("next", activeContext.next_candidates || activeContext.nextCandidates),
        ...toChipLines("risk", activeContext.risk_hints || activeContext.riskHints),
        ...toChipLines("learned", activeContext.learned_hints || activeContext.learnedHints)
    ];
    dom.continuityChips.innerHTML = chips.length > 0 ? chips.map((chip) => `<span class="chip">${escapeHtml(chip)}</span>`).join("") : emptyState("当前 active context 没有额外 hint。");
    dom.mountedContext.innerHTML = renderMountedContext(runtimeContext.mounted_context_view || runtimeContext.mountedContextView);

    dom.routeBox.innerHTML = renderRouteBox(flow, task);
    dom.experimentSummary.innerHTML = renderExperimentSummary(flow, state.experimentSummary);

    const executionJudgment = judgmentTrace.execution_judgment || judgmentTrace.executionJudgment;
    const completionJudgment = judgmentTrace.completion_judgment || judgmentTrace.completionJudgment;
    const judgmentExecutionBoundary = judgmentTrace.execution_boundary || judgmentTrace.executionBoundary;
    const judgmentRuntimeFacts = judgmentTrace.runtime_facts || judgmentTrace.runtimeFacts || {};
    const decisionCards = [];
    if (executionJudgment) {
        decisionCards.push(decisionCard("execution_judgment", executionJudgment, judgmentExecutionBoundary, judgmentRuntimeFacts));
    }
    if (completionJudgment) {
        decisionCards.push(decisionCard("completion_judgment", completionJudgment, judgmentExecutionBoundary, judgmentRuntimeFacts));
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

    dom.agentActionList.innerHTML = renderAgentActions(state.agentActions);

    const toolCards = tools.map((tool) => `
            <div class="tool-item">
                <div class="tool-item__meta">
                    <span class="task-badge" data-tone="${tool.success ? "active" : "failed"}">${escapeHtml(toolTraceStatusLabel(tool))}</span>
                    <span class="task-badge">${escapeHtml(tool.tool_name || tool.toolName)}</span>
                    <span>${formatTime(tool.created_at || tool.createdAt)}</span>
                    ${tool.elapsed_ms || tool.elapsedMs ? `<span>${escapeHtml(String(tool.elapsed_ms || tool.elapsedMs))} ms</span>` : ""}
                </div>
                <p>${escapeHtml(toolTraceSummary(tool))}</p>
            </div>
        `);
    if (toolSummary) {
        toolCards.unshift(renderToolChainSummaryCard(toolFacts, toolLabel, toolSummary));
    }
    if (executionFacts.label || executionFacts.traceSummary || executionFacts.executionId) {
        toolCards.unshift(renderExecutionSummaryCard(executionFacts));
    }
    const providerRunFiles = renderProviderRunFiles(flow);
    if (providerRunFiles) {
        toolCards.unshift(providerRunFiles);
    }
    dom.toolList.innerHTML = toolCards.length > 0 ? toolCards.join("") : emptyState("暂无 tool trace");

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

function renderAgentInventory() {
    const agents = state.agents || [];
    const readyCount = agents.filter((agent) => booleanValue(agent.ready) === true).length;
    dom.agentCount.textContent = String(agents.length);
    dom.readyAgentCount.textContent = String(readyCount);
    dom.agentInventory.innerHTML = agents.length > 0
        ? agents.map(renderAgentInventoryCard).join("")
        : emptyState("暂无 Agent Provider");
}

function renderRuntimeHealth() {
    const health = state.runtimeHealth || {};
    const runtimeHealthPlan = buildRuntimeHealthDeprioritizationPlan({
        metadata: health.metadata || {},
        providerStats: health.provider_stats || health.providerStats || []
    });
    const activeRunCount = numberValue(health.active_run_count, health.activeRunCount, 0);
    const failedRunCount = numberValue(health.failed_run_count_24h, health.failedRunCount24h, 0);
    const crashedRunCount = numberValue(health.crashed_run_count_24h, health.crashedRunCount24h, 0);
    const unavailableProviderCount = numberValue(health.unavailable_provider_count, health.unavailableProviderCount, 0);
    const authNeededProviderCount = numberValue(health.auth_needed_provider_count, health.authNeededProviderCount, 0);
    const averageDurationMs = numberValue(health.average_run_duration_ms, health.averageRunDurationMs, null);
    const activeRuns = health.active_runs || health.activeRuns || [];
    const recentFailures = health.recent_failures || health.recentFailures || [];
    const unavailableProviders = health.unavailable_providers || health.unavailableProviders || [];
    const authProblemProviders = health.auth_problem_providers || health.authProblemProviders || [];
    const providerStats = health.provider_stats || health.providerStats || [];
    dom.activeRunCount.textContent = String(activeRunCount);

    if (!health.checked_at && !health.checkedAt) {
        dom.runtimeHealth.innerHTML = emptyState("暂无 Runtime health 数据");
        return;
    }

    const metricCards = [
        overviewCard("Active", String(activeRunCount)),
        overviewCard("Failed 24h", String(failedRunCount)),
        overviewCard("Crashed 24h", String(crashedRunCount)),
        overviewCard("Unavailable", String(unavailableProviderCount)),
        overviewCard("Auth Needed", String(authNeededProviderCount)),
        overviewCard("Avg Duration", averageDurationMs === null ? "n/a" : formatDurationMs(averageDurationMs))
    ].join("");

    const providerStatsRows = providerStats.slice(0, 5)
        .map(renderProviderRuntimeStatsRow)
        .join("");

    const activeRows = activeRuns.slice(0, 4).map((run) => {
        const runId = runIdOf(run);
        const providerId = providerIdOf(run) || "unknown provider";
        const taskId = firstNonBlank(run.task_id, run.taskId, "unknown task");
        const status = firstNonBlank(run.status, "running");
        return `
            <button class="artifact-item runtime-health__row runtime-health__row--clickable" type="button" ${runId ? `data-run-id="${escapeHtml(runId)}"` : "disabled"}>
                <div>
                    <div class="artifact-item__meta">
                        <span class="task-badge" data-tone="${toneForRunStatus(status)}">${escapeHtml(status)}</span>
                        <span>${escapeHtml(providerId)}</span>
                        <span>${escapeHtml(formatTime(run.started_at || run.startedAt))}</span>
                    </div>
                    <strong class="mono">${escapeHtml(runId || "unknown run")}</strong>
                    <p>${escapeHtml(taskId)}</p>
                </div>
            </button>
        `;
    }).join("");

    const failureRows = recentFailures.slice(0, 4).map((run) => {
        const runId = runIdOf(run);
        const providerId = providerIdOf(run) || "unknown provider";
        const summary = firstNonBlank(run.summary, run.last_event_type, run.lastEventType, "failed run");
        return `
            <button class="artifact-item runtime-health__row runtime-health__row--clickable" type="button" ${runId ? `data-run-id="${escapeHtml(runId)}"` : "disabled"}>
                <div>
                    <div class="artifact-item__meta">
                        <span class="task-badge" data-tone="${toneForRunStatus(firstNonBlank(run.status, "failed"))}">${escapeHtml(firstNonBlank(run.status, "failed"))}</span>
                        <span>${escapeHtml(providerId)}</span>
                        <span>${escapeHtml(formatTime(run.started_at || run.startedAt))}</span>
                    </div>
                    <strong class="mono">${escapeHtml(runId || "unknown run")}</strong>
                    <p>${escapeHtml(preview(summary, 150))}</p>
                </div>
            </button>
        `;
    }).join("");

    const providerProblems = [...unavailableProviders, ...authProblemProviders]
        .filter((provider, index, list) => list.findIndex((item) => providerIdOf(item) === providerIdOf(provider)) === index)
        .slice(0, 4)
        .map((provider) => {
            const providerId = providerIdOf(provider) || "unknown";
            const authStatus = firstNonBlank(provider.auth_status, provider.authStatus, "unknown");
            const reason = firstNonBlank(provider.readiness_reason, provider.readinessReason, "provider not ready");
            return `
                <button class="agent-trace-row agent-trace-row--clickable" type="button" data-provider-id="${escapeHtml(providerId)}">
                    <span class="task-badge" data-tone="${authStatus === "auth_needed" ? "manual" : "paused"}">${escapeHtml(providerId)}</span>
                    <span>${escapeHtml(authStatus)} 路 ${escapeHtml(preview(reason, 120))}</span>
                </button>
            `;
        }).join("");

    dom.runtimeHealth.innerHTML = `
        <div class="runtime-health__grid">${metricCards}</div>
        ${runtimeHealthPlan.deprioritizedProviders.length > 0 ? `
            <div class="artifact-item runtime-health__deprioritization">
                <div class="artifact-item__meta">
                    <span class="task-badge" data-tone="manual">recovery window</span>
                    <span>${escapeHtml(String(runtimeHealthPlan.deprioritizedProviders.length))} provider</span>
                </div>
                <strong>${escapeHtml(runtimeHealthPlan.headline)}</strong>
                <p>${escapeHtml(runtimeHealthPlan.detail)}</p>
            </div>
        ` : ""}
        <div class="agent-trace-list runtime-health__section">
            <div class="decision-item__type">provider comparison</div>
            ${providerStatsRows || emptyState("最近 24h 暂无 provider run 统计。")}
        </div>
        <div class="agent-trace-list runtime-health__section">
            <div class="decision-item__type">active runs</div>
            ${activeRows || emptyState("当前没有 active run。")}
        </div>
        <div class="agent-trace-list runtime-health__section">
            <div class="decision-item__type">recent failures</div>
            ${failureRows || emptyState("最近没有失败 run。")}
        </div>
        <div class="agent-trace-list runtime-health__section">
            <div class="decision-item__type">provider problems</div>
            ${providerProblems || emptyState("当前没有 provider auth/ready 问题。")}
        </div>
    `;
}

function renderProviderRuntimeStatsRow(stat) {
    const providerDeprioritization = buildConsoleProviderDeprioritizationPlan({
        provider_deprioritized: stat?.metadata?.provider_deprioritized,
        deprioritized_provider: firstNonBlank(stat?.provider_id, stat?.providerId),
        deprioritization_reason: firstNonBlank(
            stat?.metadata?.deprioritization_reason,
            stat?.metadata?.deprioritizationReason
        )
    });
    const providerId = providerIdOf(stat) || "unknown";
    const totalRuns = numberValue(stat.total_runs, stat.totalRuns, 0);
    const activeRuns = numberValue(stat.active_runs, stat.activeRuns, 0);
    const completedRuns = numberValue(stat.completed_runs, stat.completedRuns, 0);
    const failedRuns = numberValue(stat.failed_runs, stat.failedRuns, 0);
    const cancelledRuns = numberValue(stat.cancelled_runs, stat.cancelledRuns, 0);
    const crashedRuns = numberValue(stat.crashed_runs, stat.crashedRuns, 0);
    const averageDurationMs = numberOrNull(stat.average_duration_ms, stat.averageDurationMs);
    const failureRate = numberOrNull(stat.failure_rate, stat.failureRate) ?? 0;
    const lastRunAt = firstNonBlank(stat.last_run_at, stat.lastRunAt);
    const lastFailureSummary = firstNonBlank(stat.last_failure_summary, stat.lastFailureSummary);
    const tone = failedRuns > 0 || crashedRuns > 0 ? "failed" : activeRuns > 0 ? "active" : "done";
    const summary = [
        `${completedRuns} completed`,
        `${failedRuns} failed`,
        crashedRuns > 0 ? `${crashedRuns} crashed` : null,
        cancelledRuns > 0 ? `${cancelledRuns} cancelled` : null
    ].filter(Boolean).join(" 路 ");
    return `
        <button class="artifact-item runtime-health__row runtime-health__row--clickable provider-stats-row" type="button" data-provider-id="${escapeHtml(providerId)}">
            <div>
                <div class="artifact-item__meta">
                    <span class="task-badge" data-tone="${tone}">${escapeHtml(formatRate(failureRate))} failed</span>
                    <span>${escapeHtml(formatCount(totalRuns, "run"))}</span>
                    <span>${escapeHtml(`${activeRuns} active`)}</span>
                    <span>avg ${escapeHtml(averageDurationMs === null ? "n/a" : formatDurationMs(averageDurationMs))}</span>
                </div>
                <strong>${escapeHtml(providerId)}</strong>
                <p>${escapeHtml(summary || "no completed status yet")}${lastRunAt ? ` 路 last ${escapeHtml(formatTime(lastRunAt))}` : ""}</p>
                ${providerDeprioritization.providerDeprioritized ? `
                    <p class="runtime-health__hint">
                        <strong>${escapeHtml(providerDeprioritization.headline)}</strong>
                        ${providerDeprioritization.detail ? ` 路 ${escapeHtml(providerDeprioritization.detail)}` : ""}
                    </p>
                ` : ""}
                ${lastFailureSummary ? `<p class="agent-warning">Last failure: ${escapeHtml(preview(lastFailureSummary, 140))}</p>` : ""}
            </div>
        </button>
    `;
}

function renderAgentRunSearch() {
    const filters = state.agentRunSearchFilters || {};
    const results = state.agentRunSearchResults || [];
    const providerId = escapeHtml(filters.providerId || "");
    const taskId = escapeHtml(filters.taskId || "");
    const limit = escapeHtml(filters.limit || "10");
    const status = filters.status || "";
    const role = filters.role || "";
    const resultRows = results.length > 0
        ? results.map(renderAgentRunSearchRow).join("")
        : emptyState("没有匹配的 agent run。可放宽 status、role 或 task_id 过滤。");

    dom.agentRunSearch.innerHTML = `
        <form class="agent-run-search__form" data-agent-run-search-form>
            <div class="agent-run-search__grid">
                <label class="field">
                    <span>Provider</span>
                    <input name="providerId" type="text" placeholder="codex" value="${providerId}">
                </label>
                <label class="field">
                    <span>Status</span>
                    <select name="status">
                        <option value="" ${status === "" ? "selected" : ""}>全部</option>
                        <option value="running" ${status === "running" ? "selected" : ""}>running</option>
                        <option value="completed" ${status === "completed" ? "selected" : ""}>completed</option>
                        <option value="failed" ${status === "failed" ? "selected" : ""}>failed</option>
                        <option value="crashed" ${status === "crashed" ? "selected" : ""}>crashed</option>
                    </select>
                </label>
                <label class="field">
                    <span>Role</span>
                    <select name="role">
                        <option value="" ${role === "" ? "selected" : ""}>全部</option>
                        <option value="planner" ${role === "planner" ? "selected" : ""}>planner</option>
                        <option value="executor" ${role === "executor" ? "selected" : ""}>executor</option>
                        <option value="judge" ${role === "judge" ? "selected" : ""}>judge</option>
                    </select>
                </label>
                <label class="field">
                    <span>Task ID</span>
                    <input name="taskId" type="text" placeholder="task_..." value="${taskId}">
                </label>
                <label class="field">
                    <span>Limit</span>
                    <input name="limit" type="number" min="1" max="100" value="${limit}">
                </label>
            </div>
            <div class="agent-action-row">
                <button class="button button--ghost" type="submit">Search Runs</button>
                <button class="link-button" type="button" data-run-search-action="reset">重置</button>
            </div>
        </form>
        <p class="agent-run-search__summary">返回 ${results.length} 条，点击 run 打开详情。</p>
        <div class="agent-run-search__results">
            ${resultRows}
        </div>
    `;
}

function renderAgentInventoryCard(agent) {
    const providerId = providerIdOf(agent) || "unknown";
    const displayName = firstNonBlank(agent.display_name, agent.displayName, providerId);
    const providerType = firstNonBlank(agent.provider_type, agent.providerType, "local_cli");
    const transport = firstNonBlank(agent.transport, "process");
    const authStatus = firstNonBlank(agent.auth_status, agent.authStatus, "unknown");
    const version = firstNonBlank(agent.version, "unknown");
    const ready = booleanValue(agent.ready) === true;
    const installed = booleanValue(agent.installed) === true;
    const selected = providerId === state.selectedAgentId;
    const readinessReason = firstNonBlank(agent.readiness_reason, agent.readinessReason);
    const checkedAt = firstNonBlank(agent.checked_at, agent.checkedAt);
    const capabilities = normalizeTextList(agent.capabilities).slice(0, 5);
    const capabilityLine = capabilities.length > 0
        ? capabilities.map((capability) => `<span class="chip">${escapeHtml(capability)}</span>`).join("")
        : `<span class="chip">no capability</span>`;
    return `
        <div class="artifact-item agent-provider-card${ready ? " is-ready" : ""}${selected ? " is-selected" : ""}" data-provider-id="${escapeHtml(providerId)}">
            <div class="artifact-item__meta">
                <span class="task-badge" data-tone="${ready ? "active" : "paused"}">${ready ? "ready" : "not ready"}</span>
                <span class="task-badge" data-tone="${installed ? "auto" : "manual"}">${installed ? "installed" : "missing"}</span>
                <span>${escapeHtml(providerType)} / ${escapeHtml(transport)}</span>
            </div>
            <strong>${escapeHtml(displayName)}</strong>
            <p class="mono">${escapeHtml(providerId)}</p>
            <div class="chip-group">${capabilityLine}</div>
            <div class="artifact-item__meta">
                <span>auth: ${escapeHtml(authStatus)}</span>
                <span>version: ${escapeHtml(version)}</span>
                ${checkedAt ? `<span>checked: ${escapeHtml(formatTime(checkedAt))}</span>` : ""}
            </div>
            ${readinessReason ? `<p>${escapeHtml(preview(readinessReason, 160))}</p>` : ""}
            <div class="agent-action-row">
                <button class="link-button" type="button" data-provider-action="view" data-provider-id="${escapeHtml(providerId)}">详情</button>
                <button class="link-button" type="button" data-provider-action="refresh" data-provider-id="${escapeHtml(providerId)}">刷新状态</button>
            </div>
        </div>
    `;
}

function renderAgentExecution(flow, task) {
    const selection = state.providerSelection || {};
    const run = state.agentRun || {};
    const events = state.agentRunEvents || [];
    const artifacts = state.agentRunArtifacts || [];
    const providerId = firstNonBlank(
        selection.selected_provider,
        selection.selectedProvider,
        run.provider_id,
        run.providerId
    );
    const selectedWorker = firstNonBlank(
        selection.selected_worker_id,
        selection.selectedWorkerId,
        run.selected_worker_id,
        run.selectedWorkerId,
        task?.assigned_worker,
        task?.assignedWorker,
        "unassigned"
    );
    const displayName = firstNonBlank(
        selection.provider_display_name,
        selection.providerDisplayName,
        run.provider_display_name,
        run.providerDisplayName,
        providerId,
        "unknown provider"
    );
    const runId = firstNonBlank(run.run_id, run.runId);
    const runStatus = firstNonBlank(run.status);
    const runSummary = firstNonBlank(run.summary);
    const selectedModelTier = firstNonBlank(selection.selected_model_tier, selection.selectedModelTier, run.selected_model_tier, run.selectedModelTier);
    const workerRole = firstNonBlank(selection.worker_role, selection.workerRole, run.worker_role, run.workerRole, "executor");
    const selectionReason = firstNonBlank(selection.selection_reason, selection.selectionReason, selection.metadata?.selection_reason, selection.metadata?.selectionReason);
    const fallbackReason = firstNonBlank(selection.fallback_reason, selection.fallbackReason, selection.metadata?.fallback_reason, selection.metadata?.fallbackReason);
    const authStatus = firstNonBlank(selection.provider_auth_status, selection.providerAuthStatus);
    const providerVersion = firstNonBlank(selection.provider_version, selection.providerVersion);
    const providerReady = booleanValue(selection.provider_ready, selection.providerReady);
    const eventPreview = events.slice(0, 3);
    const artifactPreview = artifacts.slice(0, 3);

    if (!providerId && !runId) {
        return emptyState("当前任务还没有 provider selection 或 agent run 记录。");
    }

    return `
        <div class="agent-run-card">
            <div class="artifact-item__meta">
                ${runStatus ? `<span class="task-badge" data-tone="${toneForRunStatus(runStatus)}">${escapeHtml(runStatus)}</span>` : ""}
                ${providerReady !== null ? `<span class="task-badge" data-tone="${providerReady ? "active" : "paused"}">${providerReady ? "provider ready" : "provider not ready"}</span>` : ""}
                <span>${escapeHtml(workerRole)}</span>
                ${selectedModelTier ? `<span>${escapeHtml(selectedModelTier)}</span>` : ""}
            </div>
            <strong>${escapeHtml(displayName)}</strong>
            <p class="mono">${escapeHtml([providerId, selectedWorker, runId].filter(Boolean).join(" 路 "))}</p>
            <div class="agent-run-card__grid">
                ${runId ? overviewCard("Run ID", runId) : ""}
                ${run.started_at || run.startedAt ? overviewCard("Started", formatTime(run.started_at || run.startedAt)) : ""}
                ${run.duration_ms || run.durationMs ? overviewCard("Duration", formatDurationMs(run.duration_ms || run.durationMs)) : ""}
                ${run.artifact_count || run.artifactCount ? overviewCard("Artifacts", String(run.artifact_count || run.artifactCount)) : ""}
            </div>
            ${selectionReason ? `<p>${escapeHtml(preview(selectionReason, 220))}</p>` : ""}
            ${fallbackReason ? `<p class="agent-warning">Fallback: ${escapeHtml(preview(fallbackReason, 180))}</p>` : ""}
            ${runSummary ? `<p>${escapeHtml(preview(runSummary, 220))}</p>` : ""}
            <div class="artifact-item__meta">
                ${authStatus ? `<span>auth: ${escapeHtml(authStatus)}</span>` : ""}
                ${providerVersion ? `<span>version: ${escapeHtml(providerVersion)}</span>` : ""}
            </div>
            <div class="agent-action-row">
                ${providerId ? `<button class="link-button" type="button" data-provider-id="${escapeHtml(providerId)}">View Provider</button>` : ""}
                ${runId ? `<button class="link-button" type="button" data-run-id="${escapeHtml(runId)}">View Run</button>` : ""}
            </div>
        </div>
        ${eventPreview.length > 0 ? `
            <div class="agent-trace-list">
                <div class="decision-item__type">recent events</div>
                ${eventPreview.map((event) => `
                    <div class="agent-trace-row">
                        <span class="task-badge">${escapeHtml(event.event_type || event.eventType || "event")}</span>
                        <span>${escapeHtml(preview(event.summary || event.event_id || event.eventId, 120))}</span>
                    </div>
                `).join("")}
            </div>
        ` : ""}
        ${artifactPreview.length > 0 ? `
            <div class="agent-trace-list">
                <div class="decision-item__type">agent artifacts</div>
                ${artifactPreview.map((artifact) => `
                    <div class="agent-trace-row">
                        <span class="task-badge">${escapeHtml(artifact.artifact_type || artifact.artifactType || "artifact")}</span>
                        <span>${escapeHtml(preview(artifact.title || artifact.path || artifact.artifact_id || artifact.artifactId, 120))}</span>
                    </div>
                `).join("")}
            </div>
        ` : ""}
    `;
}

function renderAgentDetail() {
    const agent = state.selectedAgent
        || (state.selectedAgentId ? state.agents.find((item) => providerIdOf(item) === state.selectedAgentId) : null);
    if (!agent) {
        dom.agentDetail.innerHTML = emptyState("点击 Agent Inventory、Runtime Health 或任务执行卡片里的 provider 后显示详情。");
        return;
    }

    const providerId = providerIdOf(agent) || state.selectedAgentId || "unknown";
    const displayName = firstNonBlank(agent.display_name, agent.displayName, providerId);
    const providerType = firstNonBlank(agent.provider_type, agent.providerType, "local_cli");
    const transport = firstNonBlank(agent.transport, "process");
    const authStatus = firstNonBlank(agent.auth_status, agent.authStatus, "unknown");
    const version = firstNonBlank(agent.version, "unknown");
    const ready = booleanValue(agent.ready) === true;
    const installed = booleanValue(agent.installed) === true;
    const readinessReason = firstNonBlank(agent.readiness_reason, agent.readinessReason, "no readiness reason");
    const activeRunCount = numberValue(agent.active_run_count, agent.activeRunCount, 0);
    const lastSeenAt = firstNonBlank(agent.checked_at, agent.checkedAt, agent.last_seen_at, agent.lastSeenAt);
    const capabilities = normalizeTextList(agent.capabilities);
    const runs = state.selectedAgentRuns || [];
    const workerId = workerIdForProvider(providerId);
    const chips = capabilities.length > 0
        ? capabilities.map((capability) => `<span class="chip">${escapeHtml(capability)}</span>`).join("")
        : `<span class="chip">no capability</span>`;

    dom.agentDetail.innerHTML = `
        <div class="artifact-item agent-detail-card">
            <div class="artifact-item__meta">
                <span class="task-badge" data-tone="${ready ? "active" : "paused"}">${ready ? "ready" : "not ready"}</span>
                <span class="task-badge" data-tone="${installed ? "auto" : "manual"}">${installed ? "installed" : "missing"}</span>
                <span>${escapeHtml(providerType)} / ${escapeHtml(transport)}</span>
            </div>
            <strong>${escapeHtml(displayName)}</strong>
            <p class="mono">${escapeHtml(providerId)}</p>
            <div class="agent-detail__grid">
                ${overviewCard("Auth", authStatus)}
                ${overviewCard("Version", version)}
                ${overviewCard("Active Runs", String(activeRunCount ?? 0))}
                ${overviewCard("Checked", lastSeenAt ? formatTime(lastSeenAt) : "unknown")}
            </div>
            <div class="chip-group">${chips}</div>
            <p>${escapeHtml(preview(readinessReason, 220))}</p>
            ${renderProviderRuntimeDiagnostics(agent, runs)}
            ${renderWorkerDispatchReadiness(state.selectedWorkerReadiness, workerId)}
            ${renderMetadataGrid(agent.metadata, 6)}
            <div class="agent-action-row">
                <button class="link-button" type="button" data-provider-action="refresh" data-provider-id="${escapeHtml(providerId)}">刷新 Provider</button>
                <button class="link-button" type="button" data-provider-action="view_runs" data-provider-id="${escapeHtml(providerId)}">筛选 Runs</button>
                <button class="link-button" type="button" data-provider-action="copy_diagnostics" data-provider-id="${escapeHtml(providerId)}">Copy Diagnostics</button>
            </div>
        </div>
        <div class="agent-trace-list">
            <div class="decision-item__type">recent provider runs</div>
            ${runs.length > 0 ? runs.slice(0, 8).map(renderAgentRunListRow).join("") : emptyState("这个 provider 暂无 run 记录。")}
        </div>
    `;
}

function renderWorkerDispatchReadiness(readiness, workerId) {
    if (!readiness) {
        return "";
    }
    const ready = booleanValue(readiness.ready) === true;
    const mode = firstNonBlank(readiness.mode, "dispatch");
    const reason = firstNonBlank(readiness.reason, readiness.dispatch_preflight_reason, readiness.dispatchPreflightReason, "no readiness reason");
    const dispatchReady = booleanValue(readiness.dispatch_preflight_ready, readiness.dispatchPreflightReady);
    const cached = booleanValue(readiness.dispatch_preflight_cached, readiness.dispatchPreflightCached);
    const activeProbe = booleanValue(readiness.dispatch_preflight_active_probe, readiness.dispatchPreflightActiveProbe);
    const probeMode = firstNonBlank(readiness.dispatch_preflight_mode, readiness.dispatchPreflightMode, "unknown");
    const metadata = readiness.dispatch_preflight_metadata || readiness.dispatchPreflightMetadata || {};
    const cliProfile = readiness.cli_profile || readiness.cliProfile || {};
    const probeArgs = normalizeTextList(metadata.dispatch_preflight_probe_args, metadata.dispatchPreflightProbeArgs);
    const commandShape = normalizeTextList(metadata.dispatch_preflight_command_shape, metadata.dispatchPreflightCommandShape);
    const launchMode = firstNonBlank(metadata.launch_mode, metadata.launchMode);
    const launchTarget = firstNonBlank(metadata.launch_target, metadata.launchTarget);
    const profileBadges = renderCliProfileBadges(cliProfile);
    const providerFailureClass = firstNonBlank(readiness.provider_failure_class, readiness.providerFailureClass, metadata.provider_failure_class, metadata.providerFailureClass);
    const providerFailureReason = firstNonBlank(readiness.provider_failure_reason, readiness.providerFailureReason, metadata.provider_failure_reason, metadata.providerFailureReason);
    const providerRetryable = booleanValue(readiness.provider_retryable, readiness.providerRetryable, metadata.provider_retryable, metadata.providerRetryable);

    return `
        <div class="agent-trace-list provider-diagnostics">
            <div class="decision-item__type">worker dispatch probe</div>
            <div class="artifact-item__meta">
                <span class="task-badge" data-tone="${ready ? "active" : "paused"}">${ready ? "dispatch ready" : "dispatch blocked"}</span>
                ${dispatchReady !== null ? `<span class="task-badge" data-tone="${dispatchReady ? "active" : "failed"}">${dispatchReady ? "preflight ok" : "preflight failed"}</span>` : ""}
                ${providerFailureClass ? `<span class="task-badge" data-tone="failed">${escapeHtml(providerFailureClass)}</span>` : ""}
                ${providerRetryable !== null ? `<span class="task-badge" data-tone="${providerRetryable ? "paused" : "failed"}">${providerRetryable ? "retryable" : "manual"}</span>` : ""}
                <span>${escapeHtml(mode)}</span>
                <span>${escapeHtml(probeMode)}</span>
                ${cached !== null ? `<span>${cached ? "cached" : "fresh probe"}</span>` : ""}
                ${activeProbe !== null ? `<span>${activeProbe ? "active probe" : "passive fallback"}</span>` : ""}
            </div>
            <div class="agent-detail__grid">
                ${overviewCard("Worker", workerId || readiness.worker_id || readiness.workerId || "unknown")}
                ${overviewCard("Launch", launchMode || "unknown")}
                ${overviewCard("Probe Args", probeArgs.length > 0 ? probeArgs.join(" ") : "n/a")}
                ${overviewCard("Command Shape", commandShape.length > 0 ? commandShape.join(" ") : "n/a")}
            </div>
            ${launchTarget ? `<p class="mono">${escapeHtml(preview(launchTarget, 220))}</p>` : ""}
            ${profileBadges}
            <p>${escapeHtml(preview(reason, 220))}</p>
            ${providerFailureReason ? `<p>${escapeHtml(preview(providerFailureReason, 220))}</p>` : ""}
            ${renderMetadataGrid(metadata, 8)}
        </div>
    `;
}

function renderCliProfileBadges(profile) {
    if (!profile || typeof profile !== "object" || Object.keys(profile).length === 0) {
        return "";
    }
    const evidence = booleanValue(profile.cli_profile_evidence_available, profile.cliProfileEvidenceAvailable);
    const entries = [
        ["yolo", profile.supports_yolo ?? profile.supportsYolo],
        ["model", profile.supports_model ?? profile.supportsModel],
        ["json", profile.supports_json_output ?? profile.supportsJsonOutput],
        ["resume", profile.supports_resume ?? profile.supportsResume],
        ["workspace", profile.supports_workspace_arg ?? profile.supportsWorkspaceArg],
        ["work-dir", profile.supports_work_dir_arg ?? profile.supportsWorkDirArg],
        ["output-file", profile.supports_output_file ?? profile.supportsOutputFile]
    ].filter(([, value]) => value !== undefined && value !== null);
    if (entries.length === 0 && evidence === null) {
        return "";
    }
    const badges = entries.map(([label, value]) => {
        const supported = booleanValue(value) === true;
        return `<span class="task-badge" data-tone="${supported ? "active" : "failed"}">${escapeHtml(`${label}: ${supported ? "yes" : "no"}`)}</span>`;
    }).join("");
    return `
        <div class="artifact-item__meta">
            <span class="task-badge" data-tone="${evidence ? "active" : "paused"}">${evidence ? "cli profile" : "profile inferred"}</span>
            ${badges}
        </div>
    `;
}

function renderProviderRuntimeDiagnostics(agent, runs) {
    const recentRuns = runs || [];
    const statusOf = (run) => String(firstNonBlank(run.status, "unknown") || "unknown").toLowerCase();
    const failedStatuses = new Set(["failed", "crashed", "error"]);
    const completedStatuses = new Set(["completed", "succeeded", "success"]);
    const activeStatuses = new Set(["queued", "starting", "running", "active"]);
    const failedRuns = recentRuns.filter((run) => failedStatuses.has(statusOf(run)));
    const completedRuns = recentRuns.filter((run) => completedStatuses.has(statusOf(run)));
    const activeRuns = recentRuns.filter((run) => activeStatuses.has(statusOf(run)));
    const durations = recentRuns
        .map((run) => numberOrNull(run.duration_ms, run.durationMs))
        .filter((duration) => duration !== null);
    const averageDurationMs = durations.length > 0
        ? durations.reduce((sum, duration) => sum + duration, 0) / durations.length
        : null;
    const failureRate = recentRuns.length > 0
        ? `${Math.round((failedRuns.length / recentRuns.length) * 100)}%`
        : "n/a";
    const lastFailure = failedRuns[0] || null;
    const lastSuccess = completedRuns[0] || null;
    const providerActiveRunCount = numberValue(agent.active_run_count, agent.activeRunCount, activeRuns.length);
    const lastFailureExitCode = numberOrNull(lastFailure?.exit_code, lastFailure?.exitCode);
    const lastFailureSummary = lastFailure
        ? firstNonBlank(lastFailure.summary, lastFailure.output_preview, lastFailure.outputPreview, lastFailure.last_event_type, lastFailure.lastEventType, "failed run")
        : null;
    const lastFailureRunId = runIdOf(lastFailure);

    return `
        <div class="agent-trace-list provider-diagnostics">
            <div class="decision-item__type">runtime diagnostics</div>
            <div class="agent-detail__grid">
                ${overviewCard("Recent Runs", String(recentRuns.length))}
                ${overviewCard("Active", String(providerActiveRunCount ?? activeRuns.length))}
                ${overviewCard("Completed", String(completedRuns.length))}
                ${overviewCard("Failed", `${failedRuns.length} / ${failureRate}`)}
                ${overviewCard("Avg Duration", averageDurationMs === null ? "n/a" : formatDurationMs(averageDurationMs))}
                ${overviewCard("Last Success", lastSuccess ? formatTime(lastSuccess.started_at || lastSuccess.startedAt) : "n/a")}
            </div>
            ${lastFailure ? `
                <button class="agent-trace-row agent-trace-row--clickable" type="button" ${lastFailureRunId ? `data-run-id="${escapeHtml(lastFailureRunId)}"` : "disabled"}>
                    <span class="task-badge" data-tone="failed">last failure</span>
                    <span>
                        ${escapeHtml(formatTime(lastFailure.started_at || lastFailure.startedAt))}
                        ${lastFailureExitCode === null ? "" : ` 路 exit ${escapeHtml(String(lastFailureExitCode))}`}
                        路 ${escapeHtml(preview(lastFailureSummary, 140))}
                    </span>
                </button>
            ` : emptyState("最近 provider runs 里没有失败记录。")}
        </div>
    `;
}

function renderAgentRunDetail() {
    const run = state.selectedAgentRun || state.agentRun;
    const events = state.selectedAgentRun ? state.selectedAgentRunEvents : state.agentRunEvents;
    const artifacts = state.selectedAgentRun ? state.selectedAgentRunArtifacts : state.agentRunArtifacts;
    if (!run) {
        dom.agentRunDetail.innerHTML = emptyState("点击任务执行卡片、Provider Detail 或 Runtime Health 里的 run 后显示详情。");
        return;
    }

    const runId = runIdOf(run) || state.selectedAgentRunId || "unknown run";
    const providerId = providerIdOf(run) || "unknown provider";
    const status = firstNonBlank(run.status, "unknown");
    const selectedWorker = firstNonBlank(run.selected_worker_id, run.selectedWorkerId, run.worker_id, run.workerId, "unassigned");
    const modelTier = firstNonBlank(run.selected_model_tier, run.selectedModelTier, run.model_tier, run.modelTier, "unknown");
    const durationMs = numberOrNull(run.duration_ms, run.durationMs);
    const artifactCount = numberOrNull(run.artifact_count, run.artifactCount);
    const summary = firstNonBlank(run.summary, run.last_event_type, run.lastEventType, "no summary");
    const taskId = firstNonBlank(run.task_id, run.taskId);
    const sessionId = firstNonBlank(run.session_id, run.sessionId);

    dom.agentRunDetail.innerHTML = `
        <div class="artifact-item agent-run-detail-card">
            <div class="artifact-item__meta">
                <span class="task-badge" data-tone="${toneForRunStatus(status)}">${escapeHtml(status)}</span>
                <span>${escapeHtml(providerId)}</span>
                <span>${escapeHtml(selectedWorker)}</span>
                <span>${escapeHtml(modelTier)}</span>
            </div>
            <strong class="mono">${escapeHtml(runId)}</strong>
            <p>${escapeHtml(preview(summary, 260))}</p>
            <div class="agent-detail__grid">
                ${overviewCard("Task", taskId || "unknown")}
                ${overviewCard("Session", sessionId || "unknown")}
                ${overviewCard("Started", formatTime(run.started_at || run.startedAt))}
                ${overviewCard("Duration", durationMs === null ? "unknown" : formatDurationMs(durationMs))}
                ${overviewCard("Artifacts", artifactCount === null ? String((artifacts || []).length) : String(artifactCount))}
                ${overviewCard("Last Event", firstNonBlank(run.last_event_type, run.lastEventType, "unknown"))}
            </div>
            ${renderMetadataGrid(run.metadata, 8)}
        </div>
        <div class="agent-trace-list">
            <div class="decision-item__type">run timeline</div>
            ${(events || []).length > 0 ? events.slice(0, 20).map(renderAgentRunEventRow).join("") : emptyState("这个 run 暂无 event。")}
        </div>
        <div class="agent-trace-list">
            <div class="decision-item__type">run artifacts</div>
            ${(artifacts || []).length > 0 ? artifacts.slice(0, 20).map(renderAgentRunArtifactRow).join("") : emptyState("这个 run 暂无 artifact。")}
        </div>
    `;
}

function renderAgentRunListRow(run) {
    const runId = runIdOf(run);
    const status = firstNonBlank(run.status, "unknown");
    const summary = firstNonBlank(run.summary, run.last_event_type, run.lastEventType, run.task_id, run.taskId, "run");
    const startedAt = firstNonBlank(run.started_at, run.startedAt);
    const durationMs = numberOrNull(run.duration_ms, run.durationMs);
    return `
        <button class="agent-run-row" type="button" ${runId ? `data-run-id="${escapeHtml(runId)}"` : "disabled"}>
            <div>
                <div class="artifact-item__meta">
                    <span class="task-badge" data-tone="${toneForRunStatus(status)}">${escapeHtml(status)}</span>
                    <span>${escapeHtml(formatTime(startedAt))}</span>
                    ${durationMs === null ? "" : `<span>${escapeHtml(formatDurationMs(durationMs))}</span>`}
                </div>
                <strong class="mono">${escapeHtml(runId || "unknown run")}</strong>
                <p>${escapeHtml(preview(summary, 140))}</p>
            </div>
        </button>
    `;
}

function renderAgentRunSearchRow(run) {
    const runId = runIdOf(run);
    const providerId = providerIdOf(run) || "unknown provider";
    const status = firstNonBlank(run.status, "unknown");
    const role = firstNonBlank(run.worker_role, run.workerRole, "executor");
    const taskId = firstNonBlank(run.task_id, run.taskId, "unknown task");
    const summary = firstNonBlank(run.summary, run.last_event_type, run.lastEventType, taskId, "run");
    const startedAt = firstNonBlank(run.started_at, run.startedAt);
    const durationMs = numberOrNull(run.duration_ms, run.durationMs);
    return `
        <button class="agent-run-row agent-run-search__row" type="button" ${runId ? `data-run-id="${escapeHtml(runId)}"` : "disabled"}>
            <div>
                <div class="artifact-item__meta">
                    <span class="task-badge" data-tone="${toneForRunStatus(status)}">${escapeHtml(status)}</span>
                    <span>${escapeHtml(providerId)}</span>
                    <span>${escapeHtml(role)}</span>
                    <span>${escapeHtml(formatTime(startedAt))}</span>
                    ${durationMs === null ? "" : `<span>${escapeHtml(formatDurationMs(durationMs))}</span>`}
                </div>
                <strong class="mono">${escapeHtml(runId || "unknown run")}</strong>
                <p>${escapeHtml(taskId)} 路 ${escapeHtml(preview(summary, 160))}</p>
            </div>
        </button>
    `;
}

function renderAgentRunEventRow(event) {
    const eventType = firstNonBlank(event.event_type, event.eventType, "event");
    const summary = firstNonBlank(event.summary, event.message, event.event_id, event.eventId, "no summary");
    return `
        <div class="agent-timeline-item">
            <div class="artifact-item__meta">
                <span class="task-badge">${escapeHtml(eventType)}</span>
                <span>${escapeHtml(formatTime(event.created_at || event.createdAt))}</span>
            </div>
            <p>${escapeHtml(preview(summary, 180))}</p>
        </div>
    `;
}

function renderAgentRunArtifactRow(artifact) {
    const artifactType = firstNonBlank(artifact.artifact_type, artifact.artifactType, "artifact");
    const title = firstNonBlank(artifact.title, artifact.path, artifact.uri, artifact.artifact_id, artifact.artifactId, "artifact");
    const summary = firstNonBlank(artifact.summary, artifact.uri, artifact.path, "");
    return `
        <div class="agent-timeline-item">
            <div class="artifact-item__meta">
                <span class="task-badge">${escapeHtml(artifactType)}</span>
                <span>${escapeHtml(formatTime(artifact.created_at || artifact.createdAt))}</span>
            </div>
            <strong>${escapeHtml(preview(title, 120))}</strong>
            ${summary ? `<p>${escapeHtml(preview(summary, 180))}</p>` : ""}
        </div>
    `;
}

function renderMetadataGrid(metadata, limit = 6) {
    if (!metadata || typeof metadata !== "object" || Array.isArray(metadata)) {
        return "";
    }
    const entries = Object.entries(metadata)
        .filter(([, value]) => value !== null && value !== undefined && value !== "")
        .slice(0, limit);
    if (entries.length === 0) {
        return "";
    }
    return `
        <div class="metadata-grid">
            ${entries.map(([key, value]) => `
                <div>
                    <span>${escapeHtml(humanizeToken(key) || key)}</span>
                    <strong>${escapeHtml(preview(typeof value === "object" ? JSON.stringify(value) : value, 80))}</strong>
                </div>
            `).join("")}
        </div>
    `;
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
        <div class="overview-card">
            <span>恢复任务</span>
            <strong>${escapeHtml(plan.summary)}</strong>
            <small class="message__hint mono">请求 ${escapeHtml(plan.requestId)}</small>
            ${plan.chips.length > 0 ? `
                <div class="agent-run-card__meta">
                    ${plan.chips.map((chip) => `<span>${escapeHtml(chip)}</span>`).join("")}
                </div>
            ` : ""}
            ${plan.error ? `<small class="message__hint">${escapeHtml(plan.error)}</small>` : ""}
        </div>
    `;
}

function renderProviderRunFiles(flow) {
    const plan = buildProviderRunFilePlan(flow);
    if (!plan.files || plan.files.length === 0) {
        return "";
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
    return `
        <div class="tool-item provider-run-files">
            <div class="tool-item__meta">
                <span class="task-badge" data-tone="active">provider files</span>
            </div>
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
    const container = button.closest(".provider-run-files");
    const previewBox = container?.querySelector("[data-provider-run-preview]");
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

function decisionCard(type, decision, executionBoundary = null, runtimeFacts = null) {
    const boundaryFacts = executionBoundaryFacts({ execution_boundary: executionBoundary }, []);
    const diagnostics = judgmentDiagnosticFacts(decision, runtimeFacts, executionBoundary);
    return `
        <div class="decision-item">
            <div class="decision-item__type">${escapeHtml(type)}</div>
            <strong>${escapeHtml(decision.summary || "no summary")}</strong>
            ${decision.rationale ? `<p>${escapeHtml(preview(decision.rationale, 220))}</p>` : ""}
            ${boundaryFacts.label || boundaryFacts.traceSummary || boundaryFacts.executionId
                ? `
                    <div class="decision-item__execution">
                        <div class="decision-item__meta">
                            <span class="task-badge" data-tone="active">execution</span>
                            ${boundaryFacts.executionId ? `<span>${escapeHtml(`id ${boundaryFacts.executionId}`)}</span>` : ""}
                            ${boundaryFacts.workerId ? `<span>${escapeHtml(`worker ${boundaryFacts.workerId}`)}</span>` : ""}
                        </div>
                        <p>${escapeHtml(preview(boundaryFacts.traceSummary || boundaryFacts.label || "execution boundary captured", 180))}</p>
                    </div>
                `
                : ""}
            ${renderDecisionDiagnostics(diagnostics)}
        </div>
    `;
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
        nonEmptyPanelCount ? `${nonEmptyPanelCount} non-empty` : null
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

function renderDecisionDiagnostics(diagnostics) {
    const metrics = diagnostics?.metrics || [];
    const candidateWorkers = diagnostics?.candidateWorkers || [];
    const evidenceRefs = diagnostics?.evidenceRefs || [];
    const unfinishedItems = diagnostics?.unfinishedItems || [];
    const cognitionRows = diagnostics?.cognitionRows || [];
    const alignmentChips = diagnostics?.alignmentChips || [];
    if (metrics.length === 0
        && candidateWorkers.length === 0
        && evidenceRefs.length === 0
        && unfinishedItems.length === 0
        && cognitionRows.length === 0
        && alignmentChips.length === 0) {
        return "";
    }
    return `
        <div class="decision-item__diagnostics">
            ${metrics.length > 0 ? `
                <div class="chip-list decision-item__chips">
                    ${metrics.map((metric) => `<span class="chip">${escapeHtml(metric)}</span>`).join("")}
                </div>
            ` : ""}
            ${renderCognitionSurfaceRows(cognitionRows)}
            ${alignmentChips.length > 0 ? `
                <div class="chip-list decision-item__chips">
                    ${alignmentChips.map((metric) => `<span class="chip">${escapeHtml(metric)}</span>`).join("")}
                </div>
            ` : ""}
            ${candidateWorkers.length > 0 ? `
                <div class="decision-item__fact-row">
                    <span class="task-badge">candidates</span>
                    <strong>${escapeHtml(candidateWorkers.join(", "))}</strong>
                </div>
            ` : ""}
            ${evidenceRefs.length > 0 ? `
                <div class="decision-item__fact-row">
                    <span class="task-badge">evidence</span>
                    <span>${escapeHtml(evidenceRefs.join(" · "))}</span>
                </div>
            ` : ""}
            ${unfinishedItems.length > 0 ? `
                <div class="decision-item__fact-row">
                    <span class="task-badge">unfinished</span>
                    <span>${escapeHtml(unfinishedItems.join(" · "))}</span>
                </div>
            ` : ""}
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
    const latestPacket = flow?.latest_packet || flow?.latestPacket || {};
    const executionJudgment = judgmentTrace.execution_judgment || judgmentTrace.executionJudgment || {};
    const completionJudgment = judgmentTrace.completion_judgment || judgmentTrace.completionJudgment || {};
    const signals = [
        valueLine("action", judgmentTrace.recommended_action || judgmentTrace.recommendedAction || executionJudgment.metadata?.action),
        valueLine("completion", completionJudgment.metadata?.completion_status || completionJudgment.metadata?.status),
        valueLine("route", routeSignal(flow)),
        valueLine("tools", toolChainLabel(flow)),
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
        .join(" 路 ");
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
        fallbackReason
    );
    const routeAlignment = booleanValue(
        cognitionSurface?.alignment?.route_worker_matches_execution_worker,
        cognitionSurface?.alignment?.routeWorkerMatchesExecutionWorker
    );
    const providerDeprioritization = buildConsoleProviderDeprioritizationPlan(
        routePreview.recovery_unpinned_recommendation
        || routePreview.recoveryUnpinnedRecommendation
        || routePreview
    );
    const routeChips = [
        modelMode ? `mode: ${humanizeToken(modelMode) || modelMode}` : null,
        preferredWorkerHint ? `hint: ${preferredWorkerHint}` : null,
        learningHintApplied === true ? "learning: applied" : null,
        learningHintApplied === false ? "learning: observed, not applied" : null,
        routeAlignment === true ? "route/execution aligned" : null,
        routeAlignment === false ? "route/execution diverged" : null,
        providerDeprioritization.chip || null
    ].filter(Boolean);
    const executionFacts = executionBoundaryFacts(flow);
    if (executionFacts.chips.length > 0) {
        routeChips.push(...executionFacts.chips);
    }
    if (!selectedWorker && !routeReason && candidateWorkers.length === 0 && routeChips.length === 0) {
        return emptyState("暂无 route preview");
    }
    return `
        <div class="route-box">
            <div class="artifact-item__meta">
                <span class="task-badge">${escapeHtml(selectedWorker)}</span>
                <span>${escapeHtml(routeSource)}</span>
                <span>${escapeHtml(taskType)}</span>
            </div>
            ${routeReason ? `<p>${escapeHtml(routeReason)}</p>` : ""}
            ${providerDeprioritization.providerDeprioritized ? `
                <p class="route-box__recovery-note">
                    <strong>${escapeHtml(providerDeprioritization.headline)}</strong>
                    ${providerDeprioritization.detail ? `<span>${escapeHtml(providerDeprioritization.detail)}</span>` : ""}
                </p>
            ` : ""}
            ${candidateWorkers.length > 0 ? `<p class="mono">${escapeHtml(candidateWorkers.join(", "))}</p>` : ""}
            ${routeChips.length > 0 ? `
                <div class="chip-group experiment-summary__chips">
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
    return `
        <div class="experiment-summary">
            <div class="artifact-item__meta">
                <span class="task-badge">${escapeHtml(experimentName)}</span>
                <span>${escapeHtml(firstNonBlank(experimentRun.task_title, experimentRun.taskTitle, taskCaseKey, "current task"))}</span>
            </div>
            <div class="chip-group experiment-summary__chips">
                ${summaryChips.map((chip) => `<span class="chip">${escapeHtml(chip)}</span>`).join("")}
            </div>
            ${summary ? `
                <div class="experiment-summary__grid">
                    ${modeSummaries.map((mode) => renderExperimentModeCard(mode, currentMode)).join("")}
                </div>
                ${renderExperimentPromptModeComparisonSection(
                    promptModeSummaries,
                    executionJudgmentPromptModeSummaries,
                    completionJudgmentPromptModeSummaries,
                    currentWorkerPromptMode,
                    currentExecutionJudgmentPromptMode,
                    currentCompletionJudgmentPromptMode
                )}
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
            <div class="artifact-item__meta">
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
            <div class="artifact-item__meta">
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
            <div class="artifact-item__meta">
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
                <div class="artifact-item__meta">
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
            <div class="artifact-item__meta">
                <span class="task-badge" data-tone="${mode === currentMode ? "active" : "default"}">${escapeHtml(mode)}</span>
                <span>${escapeHtml(humanizeToken(completionStatus) || completionStatus)}</span>
            </div>
            <strong>${escapeHtml(humanizeToken(acceptanceResult) || acceptanceResult)}</strong>
            <p>${escapeHtml(`steps ${String(numberOrNull(run.total_steps, run.totalSteps) ?? 0)} 路 cost ${formatDecimal(numberOrNull(run.total_cost, run.totalCost), 2)}`)}</p>
        </div>
    `;
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
    return parts.length > 0 ? parts.join(" 路 ") : null;
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
    return [label, facts.toolNames.map(humanizeToken).join(" -> ")].filter(Boolean).join(" 路 ");
}

function executionBoundaryFacts(flow, tools = []) {
    const boundary = flow?.execution_boundary || flow?.executionBoundary || {};
    const metadata = boundary.metadata || {};
    const status = firstNonBlank(
        boundary.execution_status,
        boundary.executionStatus,
        metadata.execution_status,
        metadata.executionStatus
    );
    const durationMs = numberOrNull(
        boundary.duration_ms,
        boundary.durationMs
    );
    const toolInvocationCount = numericValue(
        boundary.tool_invocation_count,
        boundary.toolInvocationCount
    ) ?? (Array.isArray(tools) ? tools.length : null);
    const workerId = firstNonBlank(
        boundary.worker_id,
        boundary.workerId
    );
    const traceSummary = firstNonBlank(
        boundary.trace_summary,
        boundary.traceSummary
    );
    const executionId = firstNonBlank(
        boundary.execution_id,
        boundary.executionId
    );
    const labelParts = [
        status ? humanizeToken(status) : null,
        toolInvocationCount ? formatCount(toolInvocationCount, "call") : null,
        durationMs !== null ? formatDurationMs(durationMs) : null
    ].filter(Boolean);
    const chips = [
        executionId ? `exec: ${executionId}` : null,
        workerId ? `worker: ${workerId}` : null
    ].filter(Boolean);
    return {
        status,
        durationMs,
        toolInvocationCount,
        workerId,
        traceSummary,
        executionId,
        label: labelParts.length > 0 ? labelParts.join(" · ") : null,
        chips
    };
}

function summarizeExecutionSurface(surface) {
    return buildExecutionSurfaceSummaryPlan(surface);
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
    const renderedObjectCount = numericValue(
        surface.mounted_context_rendered_object_count,
        surface.mountedContextRenderedObjectCount
    );
    const hiddenObjectCount = numericValue(
        surface.mounted_context_hidden_object_count,
        surface.mountedContextHiddenObjectCount
    );
    const renderedSelectionTraceCount = numericValue(
        surface.mounted_context_rendered_selection_trace_count,
        surface.mountedContextRenderedSelectionTraceCount
    );
    const hiddenSelectionTraceCount = numericValue(
        surface.mounted_context_hidden_selection_trace_count,
        surface.mountedContextHiddenSelectionTraceCount
    );
    const budgetTruncated = booleanValue(
        surface.mounted_context_budget_truncated,
        surface.mountedContextBudgetTruncated
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
        renderedObjectCount !== null || hiddenObjectCount !== null
            ? `${renderedObjectCount ?? 0}/${hiddenObjectCount ?? 0} objects`
            : null,
        renderedSelectionTraceCount !== null || hiddenSelectionTraceCount !== null
            ? `${renderedSelectionTraceCount ?? 0}/${hiddenSelectionTraceCount ?? 0} traces`
            : null,
        budgetTruncated === true ? "budget truncated" : null,
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

function renderCognitionSurfaceRows(rows) {
    if (!Array.isArray(rows) || rows.length === 0) {
        return "";
    }
    return `
        <div class="cognition-surface">
            ${rows.map((row) => `
                <div class="cognition-surface__row">
                    <span class="task-badge">${escapeHtml(row.label)}</span>
                    <span>${escapeHtml(row.value)}</span>
                </div>
            `).join("")}
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
                <div class="chip-group experiment-summary__chips">
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

function renderToolChainSummaryCard(facts, label, summary) {
    const metaParts = [
        label,
        facts.toolNames.length > 0 ? facts.toolNames.map(humanizeToken).join(", ") : null
    ].filter(Boolean);
    return `
        <div class="tool-item">
            <div class="tool-item__meta">
                <span class="task-badge" data-tone="auto">summary</span>
                ${metaParts.map((part) => `<span>${escapeHtml(part)}</span>`).join("")}
            </div>
            <p>${escapeHtml(preview(summary, 220))}</p>
        </div>
    `;
}

function renderExecutionSummaryCard(facts) {
    const metaParts = [
        facts.label,
        facts.executionId ? `id ${facts.executionId}` : null,
        facts.workerId ? `worker ${facts.workerId}` : null
    ].filter(Boolean);
    const body = facts.traceSummary || facts.label || "execution boundary captured";
    return `
        <div class="tool-item">
            <div class="tool-item__meta">
                <span class="task-badge" data-tone="active">execution</span>
                ${metaParts.map((part) => `<span>${escapeHtml(part)}</span>`).join("")}
            </div>
            <p>${escapeHtml(preview(body, 220))}</p>
            ${facts.traceSummary && facts.traceSummary !== body
                ? `<p class="tool-item__detail">${escapeHtml(preview(facts.traceSummary, 220))}</p>`
                : ""}
        </div>
    `;
}

function renderAgentActions(actions) {
    const plan = buildAgentActionPlan(actions);
    if (!plan.hasActions) {
        return emptyState("暂无 reconciled action");
    }
    return `
        <div class="action-summary-card">
            <strong>${escapeHtml(plan.summary)}</strong>
            <span>${escapeHtml(String(plan.counts.total))} total</span>
        </div>
        ${plan.visible.map(renderAgentActionCard).join("")}
    `;
}

function renderAgentActionCard(action) {
    const tone = action.status === "accepted"
        ? "active"
        : action.status === "rejected"
            ? "failed"
            : "paused";
    const meta = [
        action.actionType,
        `risk=${action.riskLevel}`,
        action.requiresApproval ? "requires approval" : null,
        formatTime(action.createdAt)
    ].filter(Boolean).join(" · ");
    const payload = Object.keys(action.payload || {}).length > 0
        ? `<pre>${escapeHtml(JSON.stringify(action.payload, null, 2))}</pre>`
        : "";
    return `
        <div class="artifact-item action-item">
            <div class="artifact-item__meta">
                <span class="task-badge" data-tone="${tone}">${escapeHtml(action.status)}</span>
                <span>${escapeHtml(meta)}</span>
            </div>
            <strong>${escapeHtml(action.summary || action.actionType)}</strong>
            ${action.rejectionReason ? `<p>${escapeHtml(action.rejectionReason)}</p>` : ""}
            ${payload}
        </div>
    `;
}

function toolTraceStatusLabel(tool) {
    return buildToolTraceStatusLabel(tool);
}

function toolTraceSummary(tool) {
    return buildToolTraceSummary(tool, { preview });
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

function numberValue(...values) {
    for (const value of values) {
        if (value === null || value === undefined || value === "") {
            continue;
        }
        const number = Number(value);
        if (Number.isFinite(number)) {
            return number;
        }
    }
    return null;
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
        .join(" 路 ");
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

function buildRuntimeHealthDeprioritizationPlan(input) {
    const source = input || {};
    const metadata = source.metadata || {};
    const deprioritizedProviders = normalizeTextList(
        metadata.deprioritized_providers,
        metadata.deprioritizedProviders
    );
    return {
        deprioritizedProviders,
        headline: deprioritizedProviders.length > 0
            ? `当前恢复降级窗口：${deprioritizedProviders.join(", ")}`
            : "",
        detail: deprioritizedProviders.length > 0
            ? "最近窗口内出现临时 provider 失败，恢复建议会先尝试其他 provider。"
            : ""
    };
}

function buildConsoleProviderDeprioritizationPlan(input) {
    const source = input || {};
    const providerDeprioritized = booleanValue(
        source.provider_deprioritized,
        source.providerDeprioritized,
        source.recovery_provider_deprioritized,
        source.recoveryProviderDeprioritized
    );
    const deprioritizedProvider = firstNonBlank(
        source.deprioritized_provider,
        source.deprioritizedProvider,
        source.recovery_deprioritized_provider,
        source.recoveryDeprioritizedProvider
    );
    const reason = firstNonBlank(
        source.deprioritization_reason,
        source.deprioritizationReason,
        source.recovery_deprioritization_reason,
        source.recoveryDeprioritizationReason
    );
    if (providerDeprioritized !== true || !deprioritizedProvider) {
        return {
            providerDeprioritized: false,
            deprioritizedProvider: "",
            reason: "",
            chip: "",
            headline: "",
            detail: ""
        };
    }
    return {
        providerDeprioritized: true,
        deprioritizedProvider,
        reason,
        chip: `recovery避开 ${deprioritizedProvider}`,
        headline: `恢复阶段会优先避开 ${deprioritizedProvider}`,
        detail: reason === "recent transient provider failures"
            ? "最近窗口内出现了临时 provider 失败，恢复建议会先尝试其他 provider。"
            : (reason || "")
    };
}

function humanizeToken(value) {
    const text = firstNonBlank(value);
    return text ? text.replace(/_/g, " ") : null;
}

function formatCount(value, noun) {
    return `${value} ${noun}${value === 1 ? "" : "s"}`;
}

function valueLine(label, value) {
    const text = firstNonBlank(value);
    return text ? `${label}: ${preview(text, 96)}` : null;
}

function providerIdOf(value) {
    return firstNonBlank(
        value?.provider_id,
        value?.providerId,
        value?.selected_provider,
        value?.selectedProvider
    );
}

function workerIdForProvider(providerId) {
    const normalized = firstNonBlank(providerId);
    if (!normalized) {
        return null;
    }
    if (normalized === "openclaw") {
        return "openclaw-native";
    }
    return normalized;
}

function runIdOf(value) {
    return firstNonBlank(
        value?.run_id,
        value?.runId,
        value?.agent_run_id,
        value?.agentRunId
    );
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
        ? `<div class="mounted-context__trace">${selectionTrace.slice(0, 8).map((item) => `<span class="chip">${escapeHtml(preview(item, 120))}</span>`).join("")}</div>`
        : "";
    return `
        <div class="mounted-context">
            <div class="mounted-context__meta">
                <span class="task-badge">${escapeHtml(`${nonEmptyPanels.length} panels`)}</span>
                ${view.task_id || view.taskId ? `<span class="task-badge mono">${escapeHtml(view.task_id || view.taskId)}</span>` : ""}
            </div>
            ${traceHtml}
            <div class="mounted-context__panels">
                ${nonEmptyPanels.map(renderMountedPanel).join("")}
            </div>
        </div>
    `;
}

function renderMountedPanel(panel) {
    const name = firstNonBlank(panel?.title, humanizeToken(panel?.name), panel?.name, "panel");
    const objects = Array.isArray(panel?.objects) ? panel.objects.filter(Boolean) : [];
    return `
        <div class="mounted-context__panel">
            <div class="mounted-context__panel-head">
                <strong>${escapeHtml(name)}</strong>
                <span class="task-badge">${escapeHtml(String(objects.length))}</span>
            </div>
            <div class="mounted-context__objects">
                ${objects.slice(0, 6).map(renderMountedObjectCard).join("")}
                ${objects.length > 6 ? `<div class="empty-state">还有 ${escapeHtml(String(objects.length - 6))} 个对象未展开。</div>` : ""}
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
        metadata.rehydrated_from_archive === true ? "rehydrated" : null,
        metadata.needs_archive_retrieval === true ? "archive retrieval" : null,
        metadata.needs_external_fact_refresh === true ? "external refresh" : null,
        metadata.needs_context_reopen === true ? "context reopen" : null,
        refs.length > 0 ? `refs: ${refs.length}` : null
    ].filter(Boolean);
    const detailLines = [
        summary,
        candidatePaths.length > 0 ? `targets: ${candidatePaths.slice(0, 3).map((item) => preview(item, 44)).join(" · ")}` : null,
        nextFollowups.length > 0 ? `next: ${nextFollowups.slice(0, 2).map((item) => preview(item, 60)).join(" · ")}` : null
    ].filter(Boolean);
    return `
        <div class="mounted-context__object">
            <div class="artifact-item__meta">
                <span class="task-badge">${escapeHtml(humanizeToken(type) || type)}</span>
                ${retention ? `<span class="task-badge">${escapeHtml(humanizeToken(retention) || retention)}</span>` : ""}
            </div>
            <strong>${escapeHtml(preview(title, 96))}</strong>
            ${detailLines.length > 0 ? `<p>${escapeHtml(detailLines.join("\n"))}</p>` : ""}
            ${chips.length > 0 ? `<div class="chip-group mounted-context__chips">${chips.map((chip) => `<span class="chip">${escapeHtml(chip)}</span>`).join("")}</div>` : ""}
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
    return timestampMs(task?.created_at || task?.createdAt || 0);
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

function toneForRunStatus(status) {
    switch ((status || "").toLowerCase()) {
        case "completed":
        case "succeeded":
        case "success":
            return "done";
        case "failed":
        case "error":
            return "failed";
        case "running":
        case "active":
            return "active";
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
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
    });
}

function normalizeTimestampValue(value) {
    if (value == null || value === "") {
        return value;
    }
    if (typeof value === "number") {
        return normalizeEpochNumber(value);
    }
    if (value instanceof Date) {
        return value;
    }
    const text = String(value).trim();
    if (/^-?\d+(\.\d+)?$/.test(text)) {
        return normalizeEpochNumber(Number(text));
    }
    return text;
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

function formatDurationMs(value) {
    const number = numberOrNull(value);
    if (number === null) {
        return "unknown";
    }
    if (number < 1000) {
        return `${Math.round(number)} ms`;
    }
    return `${(number / 1000).toFixed(number < 10000 ? 1 : 0)} s`;
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

function appendQueryParam(params, name, value) {
    const text = String(value || "").trim();
    if (text) {
        params.set(name, text);
    }
}

async function writeClipboard(text) {
    if (navigator.clipboard?.writeText) {
        try {
            await navigator.clipboard.writeText(text);
            return;
        } catch (error) {
            console.warn("clipboard API unavailable, falling back to textarea copy", error);
        }
    }

    const textArea = document.createElement("textarea");
    textArea.value = text;
    textArea.setAttribute("readonly", "readonly");
    textArea.style.position = "fixed";
    textArea.style.left = "-9999px";
    document.body.appendChild(textArea);
    textArea.select();
    try {
        document.execCommand("copy");
    } finally {
        document.body.removeChild(textArea);
    }
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
        const error = new Error(payload?.message || `HTTP ${response.status}`);
        error.status = response.status;
        throw error;
    }
    if (payload && payload.success === false) {
        const error = new Error(payload.message || "request failed");
        error.status = payload.status || null;
        throw error;
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

function startPolling() {
    clearInterval(state.pollingTimer);
    state.pollingTimer = setInterval(async () => {
        if (document.hidden) {
            return;
        }
        try {
            await loadHealth();
            await loadAgents(false);
            await loadRuntimeHealth(false);
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
