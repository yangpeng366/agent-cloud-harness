package com.agentcloud.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocsStructureContractTest {

    private static final Set<String> ALLOWED_TOPIC_FILES = Set.of("README.md", "PROGRESS.md");
    private static final Set<String> ALLOWED_TOPIC_DIRS = Set.of("tasks", "runs", "archive");
    private static final List<String> ROOT_ENTRY_FILES = List.of("WAKE.md", "AGENTS.md");
    private static final List<String> REQUIRED_TOPIC_HEADINGS = List.of(
        "## 命中信号",
        "## 最小阅读顺序",
        "## 稳定基线",
        "## 当前主线文档",
        "## 写回顺序"
    );
    private static final List<String> REQUIRED_PROGRESS_HEADINGS = List.of(
        "## 当前状态",
        "## 已完成",
        "## 活跃子线",
        "## 下一步",
        "## 风险"
    );
    private static final String HISTORY_HEADING = "## 历史材料";
    private static final String HISTORY_USAGE_HEADING = "## 历史材料使用规则";
    private static final List<String> REQUIRED_TOPIC_HEADING_ORDER = List.of(
        "## 命中信号",
        "## 最小阅读顺序",
        "## 稳定基线",
        "## 当前主线文档",
        "## 写回顺序"
    );
    private static final List<String> REQUIRED_PROGRESS_HEADING_ORDER = List.of(
        "## 当前状态",
        "## 已完成",
        "## 活跃子线",
        "## 下一步",
        "## 风险"
    );
    private static final List<String> REQUIRED_RUNS_HEADINGS = List.of(
        "## 命中信号",
        "## 最小阅读顺序",
        "## 当前分组",
        "## 使用规则"
    );
    private static final Pattern DATED_DOC_SUFFIX_PATTERN = Pattern.compile(".*\\d{4}-\\d{2}-\\d{2}\\.md$");
    private static final List<Pattern> CORE_DATED_DOC_PATTERNS = List.of(
        Pattern.compile(".*_EXECUTION_RECORD_\\d{4}-\\d{2}-\\d{2}\\.md$"),
        Pattern.compile(".*_ACCEPTANCE_RECORD_\\d{4}-\\d{2}-\\d{2}\\.md$"),
        Pattern.compile(".*_PRECHECK_\\d{4}-\\d{2}-\\d{2}\\.md$")
    );
    private static final List<Pattern> HISTORICAL_DATED_DOC_PATTERNS = List.of(
        Pattern.compile("GITHUB_FIRST_RELEASE_DRY_RUN_\\d{4}-\\d{2}-\\d{2}\\.md$"),
        Pattern.compile("GITHUB_FIRST_RELEASE_STAGE_PREVIEW_[A-Za-z0-9_-]+_\\d{4}-\\d{2}-\\d{2}\\.md$"),
        Pattern.compile("GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_[A-Za-z0-9_-]+_\\d{4}-\\d{2}-\\d{2}\\.md$"),
        Pattern.compile("GITHUB_FIRST_RELEASE_(?:COMMIT_SEQUENCE|STAGE_FILE_LIST|STAGED_SLICE_READY|INDEX_AUDIT)_\\d{4}-\\d{2}-\\d{2}\\.md$"),
        Pattern.compile("GOAL_RUNTIME_LANDING_DIFF_\\d{4}-\\d{2}-\\d{2}\\.md$"),
        Pattern.compile("TASK_3809507EDBBE4231_LONG_TASK_SUCCESS_RATE_DESIGN_2026-05-15\\.md$")
    );

    @Test
    void topicReadmesStayWithinLightweightWorkspaceContract() throws IOException {
        Path docsRoot = docsRoot();
        List<Path> topicDirs = topicDirs(docsRoot);

        assertFalse(topicDirs.isEmpty(), "docs/ must expose at least one topic workspace");

        for (Path topicDir : topicDirs) {
            String topicName = topicDir.getFileName().toString();
            Path topicReadme = topicDir.resolve("README.md");

            assertTrue(Files.isRegularFile(topicReadme), "topic README missing: " + topicName);

            List<String> unexpectedFiles = new ArrayList<>();
            List<String> unexpectedDirs = new ArrayList<>();
            try (Stream<Path> items = Files.list(topicDir)) {
                items.forEach(item -> {
                    String name = item.getFileName().toString();
                    if (Files.isDirectory(item)) {
                        if (!ALLOWED_TOPIC_DIRS.contains(name)) {
                            unexpectedDirs.add(name);
                        }
                    } else if (!ALLOWED_TOPIC_FILES.contains(name)) {
                        unexpectedFiles.add(name);
                    }
                });
            }

            assertTrue(unexpectedFiles.isEmpty(),
                "unexpected top-level topic files in " + topicName + ": " + unexpectedFiles);
            assertTrue(unexpectedDirs.isEmpty(),
                "unexpected top-level topic directories in " + topicName + ": " + unexpectedDirs);

            String content = readUtf8(topicReadme);
            for (String heading : REQUIRED_TOPIC_HEADINGS) {
                assertTrue(content.contains(heading),
                    "topic README missing required heading " + heading + ": " + topicName);
            }
            assertHeadingsAppearInOrder(content, REQUIRED_TOPIC_HEADING_ORDER,
                "topic README must keep the stable heading order: " + topicName);
            assertTrue(content.contains("今天仍然为真"),
                "topic README must explain which baseline docs are still true today: " + topicName);
            assertTrue(content.contains(HISTORY_HEADING) || content.contains(HISTORY_USAGE_HEADING),
                "topic README must explain historical material usage: " + topicName);

            boolean hasProgress = Files.isRegularFile(topicDir.resolve("PROGRESS.md"));
            boolean hasTasks = Files.isDirectory(topicDir.resolve("tasks"));
            boolean hasRuns = Files.isDirectory(topicDir.resolve("runs"));
            boolean hasArchive = Files.isDirectory(topicDir.resolve("archive"));

            if (hasProgress) {
                assertTrue(content.contains("PROGRESS.md"),
                    "topic README must mention PROGRESS.md once the workspace is upgraded: " + topicName);
                assertTrue(content.contains("README.md -> PROGRESS.md ->"),
                    "workspace-enabled topic README must preserve the default README -> PROGRESS -> current-line reading order: " + topicName);
                String progressContent = readUtf8(topicDir.resolve("PROGRESS.md"));
                for (String heading : REQUIRED_PROGRESS_HEADINGS) {
                    assertTrue(progressContent.contains(heading),
                        "topic PROGRESS.md missing required heading " + heading + ": " + topicName);
                }
                assertHeadingsAppearInOrder(progressContent, REQUIRED_PROGRESS_HEADING_ORDER,
                    "topic PROGRESS.md must keep the stable heading order: " + topicName);
            } else if (!hasTasks && !hasRuns && !hasArchive) {
                assertTrue(content.contains("## 当前工作区判断"),
                    "README-only topic README must explain the current workspace judgment: " + topicName);
                assertTrue(content.contains("## 何时升级"),
                    "README-only topic README must explain when the topic should be upgraded: " + topicName);
                assertTrue(content.contains("README-only"),
                    "README-only topic README must make the lightweight workspace state explicit: " + topicName);
                assertTrue(content.contains("README.md -> docs/") && content.contains("根目录主线文档"),
                    "README-only topic README must preserve the default README-only reading order: " + topicName);
            }
            if (List.of("continuity", "provider", "dialogue", "evaluation", "release").contains(topicName)) {
                assertTrue(content.contains("## 先做子主题判断"),
                    "business topic README must keep the subtopic-routing section: " + topicName);
                assertTrue(content.contains("## 当前入口建议"),
                    "business topic README must keep the current-entry-advice section: " + topicName);
                assertTrue(content.contains("| 当前问题 | 先看哪里 | 再下钻 |"),
                    "business topic README must keep the subtopic-routing decision table: " + topicName);
                assertTrue(content.contains("## 当前主线文档") && content.contains("### "),
                    "business topic README must keep grouped subsections under current mainline documents: " + topicName);
                if (hasProgress) {
                    assertTrue(content.contains("### 主题进度"),
                        "workspace-enabled business topic README must keep the theme-progress subsection: " + topicName);
                }
            }
            for (String directoryName : ALLOWED_TOPIC_DIRS) {
                if (Files.isDirectory(topicDir.resolve(directoryName))) {
                    assertTrue(content.contains(directoryName + "/"),
                        "topic README must explain enabled workspace directory " + directoryName + "/: " + topicName);
                    if ("runs".equals(directoryName)) {
                        assertTrue(content.contains("runs/README.md"),
                            "topic README must explicitly expose runs/README.md once runs/ is enabled: " + topicName);
                        assertTrue(content.contains("README.md -> PROGRESS.md -> 当前子线文档 -> runs/README.md"),
                            "runs-enabled topic README must expose runs/README.md in the default reading order: " + topicName);
                        String runsReadmeContent = readUtf8(topicDir.resolve(directoryName).resolve("README.md"));
                        for (String heading : REQUIRED_RUNS_HEADINGS) {
                            assertTrue(runsReadmeContent.contains(heading),
                                "runs/README.md missing required heading " + heading + ": " + topicName);
                        }
                        assertTrue(runsReadmeContent.contains("聚合入口"),
                            "runs/README.md must explain its aggregation-entry role: " + topicName);
                    }
                    assertTrue(Files.isRegularFile(topicDir.resolve(directoryName).resolve("README.md")),
                        "enabled workspace directory must keep a README.md entry: " + topicName + "/" + directoryName);
                }
            }
        }
    }

    @Test
    void docsReadmeKeepsTopicEntriesAndRootDocsReachable() throws IOException {
        Path docsRoot = docsRoot();
        String docsReadmeContent = readUtf8(docsRoot.resolve("README.md"));
        String docsGovernanceContent = readUtf8(docsRoot.resolve("DOCS_GOVERNANCE.md"));
        List<Path> topicDirs = topicDirs(docsRoot);
        List<String> auditSources = new ArrayList<>();
        auditSources.add(docsReadmeContent);
        List<String> topicAuditSources = new ArrayList<>();

        for (Path topicDir : topicDirs) {
            String topicName = topicDir.getFileName().toString();
            Path topicReadme = topicDir.resolve("README.md");
            String topicReadmeRelativePath = topicName + "/README.md";
            String topicReadmeContent = readUtf8(topicReadme);
            String topicState = resolveTopicState(topicDir);

            assertTrue(docsReadmeContent.contains(topicReadmeRelativePath),
                "docs/README.md must reference topic entry " + topicReadmeRelativePath);
            assertWorkspaceRowMatches(docsReadmeContent, topicName, topicState);
            if ("meta".equals(topicName)) {
                assertTrue(topicReadmeContent.contains(
                        "docs/README.md -> docs/<topic>/README.md -> DOCS_GOVERNANCE.md -> PROGRESS.md / STATE.md / DECISIONS.md"),
                    "docs/meta/README.md must keep the default docs-governance writeback chain");
            }

            auditSources.add(topicReadmeContent);
            topicAuditSources.add(topicReadmeContent);
        }

        assertTrue(docsReadmeContent.contains("meta/README.md"),
            "docs/README.md must keep the meta topic entry");
        assertTrue(docsReadmeContent.contains("DOCS_GOVERNANCE.md"),
            "docs/README.md must route governance reads to DOCS_GOVERNANCE.md");
        assertTrue(docsReadmeContent.contains("Run-DocsIndexAudit.ps1"),
            "docs/README.md must keep the docs audit script entry");
        assertTrue(docsReadmeContent.contains("DocsStructureContractTest")
                && docsReadmeContent.contains("DocsIndexAuditScriptTest"),
            "docs/README.md must keep the focused docs regression command entry");
        assertTrue(docsGovernanceContent.contains("../README.md")
                && docsGovernanceContent.contains("../STARTUP_GUIDE.md")
                && docsGovernanceContent.contains("../WAKE.md")
                && docsGovernanceContent.contains("../AGENTS.md")
                && docsGovernanceContent.contains("../STATE.md")
                && docsGovernanceContent.contains("../DECISIONS.md"),
            "docs/DOCS_GOVERNANCE.md must use file-local relative paths for root entries");
        assertTrue(docsGovernanceContent.contains("README.md")
                && docsGovernanceContent.contains("meta/README.md")
                && docsGovernanceContent.contains("../scripts/Run-DocsIndexAudit.ps1"),
            "docs/DOCS_GOVERNANCE.md must use file-local relative paths for governance entrypoints");
        assertTrue(docsReadmeContent.contains("## 按角色找入口"),
            "docs/README.md must keep the by-role entry section");
        assertTrue(docsReadmeContent.contains("../STARTUP_GUIDE.md"),
            "docs/README.md must keep the startup/verify role entry");
        assertTrue(docsReadmeContent.contains("meta/README.md")
                && docsReadmeContent.contains("DOCS_GOVERNANCE.md")
                && docsReadmeContent.contains("meta/PROGRESS.md"),
            "docs/README.md must keep the docs-governance role entry and its follow-up reads");
        assertTrue(docsReadmeContent.contains("../WAKE.md")
                && docsReadmeContent.contains("../AGENTS.md"),
            "docs/README.md must keep the agent handoff role entry");
        assertTrue(docsReadmeContent.contains("../STATE.md")
                && docsReadmeContent.contains("../DECISIONS.md"),
            "docs/README.md must keep the continuity-read role entry");

        try (Stream<Path> files = Files.list(docsRoot)) {
            List<Path> rootDocs = files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .filter(path -> !"README.md".equals(path.getFileName().toString()))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();

            for (Path rootDoc : rootDocs) {
                String fileName = rootDoc.getFileName().toString();
                boolean referenced = auditSources.stream().anyMatch(content -> content.contains(fileName));
                boolean referencedFromTopic = topicAuditSources.stream().anyMatch(content -> content.contains(fileName));
                assertTrue(referenced,
                    "root-level docs Markdown must be reachable from docs/README.md or a topic README: " + fileName);
                assertTrue(referencedFromTopic,
                    "root-level docs Markdown must be reachable from at least one topic README, not only docs/README.md: "
                        + fileName);
            }
        }
    }

    @Test
    void datedRootDocsFollowCoreContractOrExplicitHistoricalExceptions() throws IOException {
        Path docsRoot = docsRoot();

        List<String> violatingFiles;
        try (Stream<Path> files = Files.list(docsRoot)) {
            violatingFiles = files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> !"README.md".equals(name))
                .filter(name -> DATED_DOC_SUFFIX_PATTERN.matcher(name).matches())
                .filter(name -> !matchesAny(name, CORE_DATED_DOC_PATTERNS))
                .filter(name -> !matchesAny(name, HISTORICAL_DATED_DOC_PATTERNS))
                .sorted()
                .collect(Collectors.toList());
        }

        assertTrue(violatingFiles.isEmpty(),
            "dated docs must use the current core naming contract or an explicitly grandfathered historical exception: "
                + violatingFiles);
    }

    @Test
    void rootEntryDocsStayInSyncWithWorkspaceStateAndReadingOrder() throws IOException {
        Path repoRoot = Paths.get("").toAbsolutePath().normalize();
        Path docsRoot = docsRoot();
        List<TopicWorkspaceState> topicWorkspaceStates = topicDirs(docsRoot).stream()
            .map(topicDir -> {
                try {
                    return new TopicWorkspaceState(topicDir.getFileName().toString(), resolveTopicState(topicDir));
                } catch (IOException exception) {
                    throw new IllegalStateException("failed to resolve topic state for " + topicDir, exception);
                }
            })
            .toList();

        boolean hasWorkspaceEnabledTopic = topicWorkspaceStates.stream()
            .anyMatch(topicState -> "workspace_enabled".equals(topicState.state()));
        boolean hasReadmeOnlyTopic = topicWorkspaceStates.stream()
            .anyMatch(topicState -> "readme_only".equals(topicState.state()));

        for (String rootEntryFile : ROOT_ENTRY_FILES) {
            String content = readUtf8(repoRoot.resolve(rootEntryFile));
            for (TopicWorkspaceState topicWorkspaceState : topicWorkspaceStates) {
                assertRootEntryWorkspaceRowMatches(
                    content,
                    rootEntryFile,
                    topicWorkspaceState.topicName(),
                    topicWorkspaceState.state()
                );
            }
            if (hasWorkspaceEnabledTopic) {
                assertTrue(content.contains("- 已启用 `PROGRESS.md` 的主题：`README.md -> PROGRESS.md -> 当前主线文档`"),
                    rootEntryFile + " must preserve the default reading order for topics that already enabled PROGRESS.md");
            }
            if (hasReadmeOnlyTopic) {
                assertTrue(content.contains("- `README-only` 主题：`README.md -> docs/` 根目录主线文档"),
                    rootEntryFile + " must preserve the default reading order for README-only topics");
            }
        }
    }

    @Test
    void readmeAndStartupGuideKeepRootNavigationContracts() throws IOException {
        Path repoRoot = Paths.get("").toAbsolutePath().normalize();

        String readmeContent = readUtf8(repoRoot.resolve("README.md"));
        assertTrue(readmeContent.contains("文档导航"),
            "README.md must preserve the docs navigation section");
        assertTrue(readmeContent.contains("docs/README.md"),
            "README.md must route readers to docs/README.md");
        assertTrue(readmeContent.contains("docs/meta/README.md"),
            "README.md must route readers to docs/meta/README.md");
        assertTrue(readmeContent.contains("docs/DOCS_GOVERNANCE.md"),
            "README.md must route readers to docs/DOCS_GOVERNANCE.md");
        assertTrue(readmeContent.contains("WAKE.md") && readmeContent.contains("AGENTS.md"),
            "README.md must route agent readers to WAKE.md and AGENTS.md");
        assertTrue(readmeContent.contains("STATE.md") && readmeContent.contains("DECISIONS.md"),
            "README.md must route continuity reads to STATE.md and DECISIONS.md");

        String startupGuideContent = readUtf8(repoRoot.resolve("STARTUP_GUIDE.md"));
        assertTrue(startupGuideContent.contains("## 本文边界"),
            "STARTUP_GUIDE.md must preserve the boundary section");
        assertTrue(startupGuideContent.contains("docs/README.md"),
            "STARTUP_GUIDE.md must redirect non-startup work to docs/README.md");
        assertTrue(startupGuideContent.contains("WAKE.md") && startupGuideContent.contains("AGENTS.md"),
            "STARTUP_GUIDE.md must route agent work to WAKE.md and AGENTS.md");
        assertTrue(startupGuideContent.contains("docs/dialogue/README.md"),
            "STARTUP_GUIDE.md must route UI/browser work to docs/dialogue/README.md");
        assertTrue(startupGuideContent.contains("docs/provider/README.md"),
            "STARTUP_GUIDE.md must route provider/worker work to docs/provider/README.md");
        assertTrue(startupGuideContent.contains("STATE.md") && startupGuideContent.contains("DECISIONS.md"),
            "STARTUP_GUIDE.md must route continuity reads to STATE.md and DECISIONS.md");
    }

    @Test
    void agentsDocStaysAsAgentEntryInsteadOfProjectEncyclopedia() throws IOException {
        Path repoRoot = Paths.get("").toAbsolutePath().normalize();
        String agentsContent = readUtf8(repoRoot.resolve("AGENTS.md"));

        assertTrue(agentsContent.contains("## 开工红线"),
            "AGENTS.md must keep the start-work guardrails section");
        assertTrue(agentsContent.contains("## 项目事实入口"),
            "AGENTS.md must route project facts to the dedicated baseline docs");
        assertTrue(agentsContent.contains("docs/ARCHITECTURE.md")
                && agentsContent.contains("docs/API_CONTRACTS.md")
                && agentsContent.contains("docs/SPEC.md")
                && agentsContent.contains("docs/TROUBLESHOOT.md"),
            "AGENTS.md must route stable project facts to the dedicated docs baselines");
        assertFalse(agentsContent.contains("## 项目概述"),
            "AGENTS.md should not grow back into a project overview document");
        assertFalse(agentsContent.contains("## 技术栈"),
            "AGENTS.md should not duplicate the project technical-stack baseline");
        assertFalse(agentsContent.contains("## 代码组织"),
            "AGENTS.md should not duplicate the source-tree baseline");
        assertFalse(agentsContent.contains("## API 端点速查"),
            "AGENTS.md should not duplicate the API contracts baseline");
    }

    private Path docsRoot() {
        Path docsRoot = Paths.get("").toAbsolutePath().normalize().resolve("docs");
        assertTrue(Files.isDirectory(docsRoot), "docs directory not found under project root");
        return docsRoot;
    }

    private List<Path> topicDirs(Path docsRoot) throws IOException {
        try (Stream<Path> dirs = Files.list(docsRoot)) {
            return dirs
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }
    }

    private String readUtf8(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private String resolveTopicState(Path topicDir) throws IOException {
        boolean hasReadme = Files.isRegularFile(topicDir.resolve("README.md"));
        boolean hasProgress = Files.isRegularFile(topicDir.resolve("PROGRESS.md"));
        boolean hasTasks = Files.isDirectory(topicDir.resolve("tasks"));
        boolean hasRuns = Files.isDirectory(topicDir.resolve("runs"));
        boolean hasArchive = Files.isDirectory(topicDir.resolve("archive"));

        List<String> unexpectedFiles = new ArrayList<>();
        List<String> unexpectedDirs = new ArrayList<>();
        try (Stream<Path> items = Files.list(topicDir)) {
            items.forEach(item -> {
                String name = item.getFileName().toString();
                if (Files.isDirectory(item)) {
                    if (!ALLOWED_TOPIC_DIRS.contains(name)) {
                        unexpectedDirs.add(name);
                    }
                } else if (!ALLOWED_TOPIC_FILES.contains(name)) {
                    unexpectedFiles.add(name);
                }
            });
        }

        if (!hasReadme) {
            return "missing_readme";
        }
        if (!unexpectedFiles.isEmpty() || !unexpectedDirs.isEmpty()) {
            return "contract_violation";
        }
        if (!hasProgress && !hasTasks && !hasRuns && !hasArchive) {
            return "readme_only";
        }
        return "workspace_enabled";
    }

    private void assertWorkspaceRowMatches(String docsReadmeContent, String topicName, String topicState) {
        Pattern rowPattern = Pattern.compile("(?m)^\\|\\s*" + Pattern.quote("`" + topicName + "/`") + "\\s*\\|.*$");
        Matcher matcher = rowPattern.matcher(docsReadmeContent);

        assertTrue(matcher.find(), "docs/README.md must expose a workspace status row for " + topicName);

        String row = matcher.group();
        if ("readme_only".equals(topicState)) {
            assertTrue(row.contains("仅 `README.md`"),
                "docs/README.md must mark " + topicName + " as README-only when no workspace layer is enabled");
        } else if ("workspace_enabled".equals(topicState)) {
            assertFalse(row.contains("仅 `README.md`"),
                "docs/README.md must not mark " + topicName + " as README-only after the workspace is upgraded");
            Path topicRunsReadme = docsRoot().resolve(topicName).resolve("runs").resolve("README.md");
            if (Files.isRegularFile(topicRunsReadme)) {
                assertTrue(row.contains(topicName + "/runs/README.md"),
                    "docs/README.md must expose runs/README.md in the default reading path once runs/ is enabled: " + topicName);
            }
        }
    }

    private void assertRootEntryWorkspaceRowMatches(
        String rootEntryContent,
        String rootEntryFile,
        String topicName,
        String topicState
    ) {
        if (!"workspace_enabled".equals(topicState) && !"readme_only".equals(topicState)) {
            return;
        }

        String expectedState = "workspace_enabled".equals(topicState) ? "README.md + PROGRESS.md" : "README-only";
        Pattern rowPattern = Pattern.compile(
            "(?m)^-\\s*" + Pattern.quote("`" + topicName + "/`") + ":\\s*" + Pattern.quote("`" + expectedState + "`") + "\\s*$"
        );

        assertTrue(rowPattern.matcher(rootEntryContent).find(),
            rootEntryFile + " must publish current workspace state for " + topicName + " as " + expectedState);
    }

    private boolean matchesAny(String value, List<Pattern> patterns) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(value).matches());
    }

    private void assertHeadingsAppearInOrder(String content, List<String> headings, String message) {
        int previousIndex = -1;
        List<String> lines = content.lines().toList();
        for (String heading : headings) {
            int index = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equals(heading)) {
                    index = i;
                    break;
                }
            }
            assertTrue(index >= 0, message + " (missing " + heading + ")");
            assertTrue(index > previousIndex, message + " (out of order at " + heading + ")");
            previousIndex = index;
        }
    }

    private record TopicWorkspaceState(String topicName, String state) {
    }
}
