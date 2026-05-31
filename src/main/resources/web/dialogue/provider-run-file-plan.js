export function buildProviderRunFilePlan(flow) {
    const surface = flow?.runtime_cognition_surface?.execution
        || flow?.runtimeCognitionSurface?.execution
        || {};
    const task = flow?.task || {};
    const taskId = firstNonBlank(task.id, flow?.task_id, flow?.taskId);
    if (!taskId || Object.keys(surface).length === 0) {
        return { taskId: taskId || "", files: [] };
    }
    const candidates = [
        fileCandidate("last_message", "最后输出", surface.provider_last_message_path, surface.providerLastMessagePath),
        fileCandidate("events", "事件日志", surface.provider_event_log_path, surface.providerEventLogPath),
        fileCandidate("stdout", "标准输出", surface.provider_stdout_path, surface.providerStdoutPath),
        fileCandidate("metadata", "运行元数据", surface.provider_run_metadata_path, surface.providerRunMetadataPath),
        fileCandidate("prompt", "提示词", surface.provider_prompt_path, surface.providerPromptPath)
    ];
    return {
        taskId,
        runDir: firstNonBlank(surface.provider_run_dir, surface.providerRunDir),
        files: candidates.filter((item) => item.path)
    };
}

function fileCandidate(kind, label, ...paths) {
    const path = firstNonBlank(...paths);
    return {
        kind,
        label,
        path,
        previewLabel: path ? `${label}: ${compactPath(path)}` : ""
    };
}

function compactPath(path) {
    const text = firstNonBlank(path);
    if (!text) {
        return "";
    }
    const normalized = text.replaceAll("\\", "/");
    const parts = normalized.split("/").filter(Boolean);
    if (parts.length <= 3) {
        return text;
    }
    return `.../${parts.slice(-3).join("/")}`;
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
    }
    return "";
}
