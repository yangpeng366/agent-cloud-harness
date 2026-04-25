package com.agentcloud.tool;

import com.agentcloud.model.Worker;

import java.nio.file.Path;

/**
 * Worker 工具调用边界策略。
 */
public class ToolPolicy {

    public void ensureToolAllowed(Worker worker, String toolName) {
        if (worker == null) {
            throw new IllegalArgumentException("worker not found");
        }
        if (worker.suggestOnly()) {
            throw new IllegalArgumentException("worker is suggest-only and cannot invoke tools");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("tool_name is required");
        }
        if (worker.toolCapabilities() == null || !worker.toolCapabilities().contains(toolName)) {
            throw new IllegalArgumentException("tool not allowed for worker: " + toolName);
        }
    }

    public Path resolveAllowedPath(Worker worker, String rawPath, boolean writeMode) {
        if (worker == null) {
            throw new IllegalArgumentException("worker not found");
        }
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        if (worker.toolScope() == null || worker.toolScope().isEmpty()) {
            throw new IllegalArgumentException("worker has no tool scope");
        }

        Path candidate = Path.of(rawPath);
        if (!candidate.isAbsolute()) {
            candidate = Path.of(worker.toolScope().get(0)).resolve(candidate);
        }
        candidate = candidate.toAbsolutePath().normalize();

        for (String scopeValue : worker.toolScope()) {
            if (scopeValue == null || scopeValue.isBlank()) {
                continue;
            }
            Path scope = Path.of(scopeValue).toAbsolutePath().normalize();
            if (candidate.startsWith(scope)) {
                return candidate;
            }
        }

        throw new IllegalArgumentException(
            (writeMode ? "write path outside allowed scope: " : "read path outside allowed scope: ") + candidate
        );
    }
}
