package com.agentcloud.tool;

import com.agentcloud.engine.router.WorkerRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

/**
 * 列目录工具。
 */
public class ListFilesTool extends AbstractLocalFileTool {

    public ListFilesTool(WorkerRegistry workerRegistry, ToolPolicy toolPolicy) {
        super(workerRegistry, toolPolicy);
    }

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public ToolResult invoke(ToolRequest request) throws IOException {
        Path root = resolvePath(request, false, true);
        boolean recursive = booleanArg(request.arguments(), "recursive", false);
        int maxEntries = intArg(request.arguments(), "max_entries", 100, 300);

        if (!Files.exists(root)) {
            throw new IllegalArgumentException("path does not exist: " + root);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("path is not a directory: " + root);
        }

        List<String> entries;
        try (Stream<Path> stream = recursive ? Files.walk(root) : Files.list(root)) {
            entries = stream
                .filter(path -> !path.equals(root))
                .sorted(Comparator.comparing(Path::toString))
                .limit(maxEntries)
                .map(path -> {
                    String type = Files.isDirectory(path) ? "dir" : "file";
                    return type + ": " + root.relativize(path);
                })
                .toList();
        }

        boolean truncated = entries.size() >= maxEntries;
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", root.toString());
        metadata.put("recursive", recursive);
        metadata.put("entry_count", entries.size());
        metadata.put("truncated", truncated);

        String output = String.join("\n", entries);
        String summary = "listed " + entries.size() + " entries under " + root;
        if (truncated) {
            summary += " (truncated)";
        }
        return new ToolResult(true, summary, output, metadata);
    }
}
