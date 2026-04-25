package com.agentcloud.tool;

import com.agentcloud.engine.router.WorkerRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;

/**
 * 文本读取工具。
 */
public class ReadFileTool extends AbstractLocalFileTool {

    public ReadFileTool(WorkerRegistry workerRegistry, ToolPolicy toolPolicy) {
        super(workerRegistry, toolPolicy);
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public ToolResult invoke(ToolRequest request) throws IOException {
        Path file = resolvePath(request, false, false);
        int maxBytes = intArg(request.arguments(), "max_bytes", 64 * 1024, 256 * 1024);

        if (!Files.exists(file)) {
            throw new IllegalArgumentException("file does not exist: " + file);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("path is not a file: " + file);
        }

        long size = Files.size(file);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(maxBytes + 1);
        }

        boolean truncated = size > maxBytes || bytes.length > maxBytes;
        if (bytes.length > maxBytes) {
            bytes = Arrays.copyOf(bytes, maxBytes);
        }

        String content = new String(bytes, StandardCharsets.UTF_8);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", file.toString());
        metadata.put("bytes_read", bytes.length);
        metadata.put("truncated", truncated);

        String summary = "read " + bytes.length + " bytes from " + file;
        if (truncated) {
            summary += " (truncated)";
        }
        return new ToolResult(true, summary, content, metadata);
    }
}
