package com.agentcloud.tool;

import com.agentcloud.engine.router.WorkerRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 目录/文件文本搜索工具。
 */
public class SearchTextTool extends AbstractLocalFileTool {

    public SearchTextTool(WorkerRegistry workerRegistry, ToolPolicy toolPolicy) {
        super(workerRegistry, toolPolicy);
    }

    @Override
    public String name() {
        return "search_text";
    }

    @Override
    public ToolResult invoke(ToolRequest request) throws IOException {
        Path root = resolvePath(request, false, true);
        String query = stringArg(request.arguments(), "query");
        boolean recursive = booleanArg(request.arguments(), "recursive", true);
        boolean ignoreCase = booleanArg(request.arguments(), "ignore_case", false);
        int maxResults = intArg(request.arguments(), "max_results", 30, 100);

        if (!Files.exists(root)) {
            throw new IllegalArgumentException("path does not exist: " + root);
        }

        List<String> matches = new ArrayList<>();
        if (Files.isRegularFile(root)) {
            collectFileMatches(root, query, ignoreCase, maxResults, matches, root.getParent());
        } else {
            try (Stream<Path> stream = recursive ? Files.walk(root) : Files.list(root)) {
                List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
                for (Path file : files) {
                    collectFileMatches(file, query, ignoreCase, maxResults, matches, root);
                    if (matches.size() >= maxResults) {
                        break;
                    }
                }
            }
        }

        boolean truncated = matches.size() >= maxResults;
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", root.toString());
        metadata.put("query", query);
        metadata.put("match_count", matches.size());
        metadata.put("truncated", truncated);
        metadata.put("recursive", recursive);
        metadata.put("ignore_case", ignoreCase);

        String summary = "found " + matches.size() + " matches for query '" + query + "'";
        if (truncated) {
            summary += " (truncated)";
        }
        return new ToolResult(true, summary, String.join("\n", matches), metadata);
    }

    private void collectFileMatches(Path file, String query, boolean ignoreCase, int maxResults,
                                    List<String> matches, Path displayRoot) {
        String normalizedQuery = ignoreCase ? query.toLowerCase(Locale.ROOT) : query;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String haystack = ignoreCase ? line.toLowerCase(Locale.ROOT) : line;
                if (haystack.contains(normalizedQuery)) {
                    String displayPath = displayRoot != null && file.startsWith(displayRoot)
                        ? displayRoot.relativize(file).toString()
                        : file.toString();
                    matches.add(displayPath + ":" + lineNumber + ": " + line);
                    if (matches.size() >= maxResults) {
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
            // 非文本文件或编码异常直接跳过，避免搜索流程被单个文件中断。
        }
    }
}
