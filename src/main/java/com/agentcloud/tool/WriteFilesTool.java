package com.agentcloud.tool;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受控批量文本写入工具。
 */
public class WriteFilesTool extends AbstractLocalFileTool {

    public WriteFilesTool(WorkerRegistry workerRegistry, ToolPolicy toolPolicy) {
        super(workerRegistry, toolPolicy);
    }

    @Override
    public String name() {
        return "write_files";
    }

    @Override
    public String description() {
        return "Write multiple UTF-8 text files under an allowed base directory in one grounded step.";
    }

    @Override
    public String argumentContract() {
        return "{\"base_path\":\"demo-app\",\"files\":[{\"path\":\"README.md\",\"content\":\"# Demo\"},{\"path\":\"src/main.js\",\"content\":\"console.log('demo');\"}],\"overwrite\":true}";
    }

    @Override
    public ToolResult invoke(ToolRequest request) throws IOException {
        Worker worker = requireWorker(request);
        String basePathArg = firstNonBlank(
            optionalStringArg(request.arguments(), "base_path", ""),
            optionalStringArg(request.arguments(), "path", "")
        );
        if (basePathArg.isBlank()) {
            throw new IllegalArgumentException("base_path is required");
        }

        Path baseDir = toolPolicy.resolveAllowedPath(worker, basePathArg, true);
        List<FileWriteSpec> fileSpecs = parseFileSpecs(request.arguments().get("files"));
        if (fileSpecs.isEmpty()) {
            throw new IllegalArgumentException("files is required");
        }
        boolean overwrite = booleanArg(request.arguments(), "overwrite", true);

        Files.createDirectories(baseDir);

        int totalBytes = 0;
        List<String> writtenPaths = new ArrayList<>();
        for (FileWriteSpec spec : fileSpecs) {
            Path relative = normalizeRelativePath(spec.path());
            Path target = baseDir.resolve(relative).normalize();
            if (!target.startsWith(baseDir)) {
                throw new IllegalArgumentException("file path escapes base_path: " + spec.path());
            }
            if (Files.exists(target) && Files.isDirectory(target)) {
                throw new IllegalArgumentException("target path is a directory: " + target);
            }
            if (!overwrite && Files.exists(target)) {
                throw new IllegalArgumentException("target file already exists: " + target);
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            byte[] bytes = spec.content().getBytes(StandardCharsets.UTF_8);
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            totalBytes += bytes.length;
            writtenPaths.add(target.toString());
        }

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("base_path", baseDir.toString());
        metadata.put("file_count", writtenPaths.size());
        metadata.put("total_bytes_written", totalBytes);
        metadata.put("overwrite", overwrite);
        metadata.put("written_paths", List.copyOf(writtenPaths));

        String summary = "wrote " + writtenPaths.size() + " files under " + baseDir;
        return new ToolResult(true, summary, summary, metadata);
    }

    @SuppressWarnings("unchecked")
    private List<FileWriteSpec> parseFileSpecs(Object rawFiles) {
        if (rawFiles == null) {
            return List.of();
        }
        ArrayList<FileWriteSpec> specs = new ArrayList<>();
        if (rawFiles instanceof Map<?, ?> mappedFiles) {
            for (Map.Entry<?, ?> entry : mappedFiles.entrySet()) {
                String path = entry.getKey() == null ? "" : entry.getKey().toString();
                String content = entry.getValue() == null ? "" : entry.getValue().toString();
                if (!path.isBlank()) {
                    specs.add(new FileWriteSpec(path, content));
                }
            }
            return List.copyOf(specs);
        }
        if (!(rawFiles instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException("files must be an array or object map");
        }
        for (Object item : iterable) {
            if (!(item instanceof Map<?, ?> fileMap)) {
                throw new IllegalArgumentException("files entries must be objects");
            }
            Object pathValue = fileMap.get("path");
            if (pathValue == null || pathValue.toString().isBlank()) {
                throw new IllegalArgumentException("each files entry requires path");
            }
            Object contentValue = fileMap.get("content");
            specs.add(new FileWriteSpec(pathValue.toString(), contentValue == null ? "" : contentValue.toString()));
        }
        return List.copyOf(specs);
    }

    private Path normalizeRelativePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("file path is required");
        }
        Path relative = Path.of(rawPath).normalize();
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("file path must be relative: " + rawPath);
        }
        if (relative.getNameCount() == 0 || relative.startsWith("..")) {
            throw new IllegalArgumentException("file path escapes base directory: " + rawPath);
        }
        return relative;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record FileWriteSpec(String path, String content) {
        private FileWriteSpec {
            if (path == null) {
                path = "";
            }
            if (content == null) {
                content = "";
            }
        }
    }
}
