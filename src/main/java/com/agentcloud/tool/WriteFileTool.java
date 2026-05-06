package com.agentcloud.tool;

import com.agentcloud.engine.router.WorkerRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;

/**
 * 文本写入工具。
 */
public class WriteFileTool extends AbstractLocalFileTool {

    public WriteFileTool(WorkerRegistry workerRegistry, ToolPolicy toolPolicy) {
        super(workerRegistry, toolPolicy);
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Write UTF-8 text to an allowed file path.";
    }

    @Override
    public String argumentContract() {
        return "{\"path\":\"result.txt\",\"content\":\"hello\",\"append\":false}";
    }

    @Override
    public ToolResult invoke(ToolRequest request) throws IOException {
        Path file = resolvePath(request, true, false);
        String content = stringArg(request.arguments(), "content");
        boolean append = booleanArg(request.arguments(), "append", false);

        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (append) {
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", file.toString());
        metadata.put("bytes_written", bytes.length);
        metadata.put("append", append);

        String summary = append
            ? "appended " + bytes.length + " bytes to " + file
            : "wrote " + bytes.length + " bytes to " + file;
        return new ToolResult(true, summary, summary, metadata);
    }
}
