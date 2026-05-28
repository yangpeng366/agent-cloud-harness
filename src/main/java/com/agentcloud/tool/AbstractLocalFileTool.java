package com.agentcloud.tool;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Worker;

import java.nio.file.Path;
import java.util.Map;

/**
 * 本地文件类工具共享基类。
 */
abstract class AbstractLocalFileTool implements Tool {
    protected final WorkerRegistry workerRegistry;
    protected final ToolPolicy toolPolicy;

    protected AbstractLocalFileTool(WorkerRegistry workerRegistry, ToolPolicy toolPolicy) {
        this.workerRegistry = workerRegistry;
        this.toolPolicy = toolPolicy;
    }

    protected Worker requireWorker(ToolRequest request) {
        Worker worker = workerRegistry.get(request.workerId());
        if (worker == null) {
            throw new IllegalArgumentException("worker not found: " + request.workerId());
        }
        toolPolicy.ensureToolAllowed(worker, name());
        return worker;
    }

    protected Path resolvePath(ToolRequest request, boolean writeMode, boolean allowScopeRootDefault) {
        Worker worker = requireWorker(request);
        Object raw = request.arguments().get("path");
        if ((raw == null || raw.toString().isBlank()) && allowScopeRootDefault) {
            return toolPolicy.resolveAllowedPath(worker, worker.toolScope().get(0), writeMode, request.taskMetadata());
        }
        return toolPolicy.resolveAllowedPath(worker, stringArg(request.arguments(), "path"), writeMode, request.taskMetadata());
    }

    protected String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        String result = value.toString();
        if (result.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return result;
    }

    protected String optionalStringArg(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        String result = value.toString();
        return result.isBlank() ? defaultValue : result;
    }

    protected boolean booleanArg(Map<String, Object> arguments, String key, boolean defaultValue) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(value.toString());
    }

    protected int intArg(Map<String, Object> arguments, String key, int defaultValue, int maxValue) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            parsed = Integer.parseInt(value.toString());
        }
        if (parsed < 1) {
            return 1;
        }
        return Math.min(parsed, maxValue);
    }
}
