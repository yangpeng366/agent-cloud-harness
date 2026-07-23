package com.agentcloud.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocsIndexAuditScriptTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void docsIndexAuditScriptExecutesAndProjectsRootEntryCoverage() throws Exception {
        Assumptions.assumeTrue(isWindowsHost(), "docs audit script contract test runs on Windows PowerShell");

        ProcessResult result = runAuditScript();
        assertEquals(0, result.exitCode(), "docs audit script must exit cleanly");

        JsonNode payload = MAPPER.readTree(result.stdout());
        JsonNode summary = payload.path("summary");

        assertTrue(summary.path("passed").asBoolean(), "docs audit summary must report passed=true");
        assertEquals(0, summary.path("violation_count").asInt(), "docs audit must not report violations");
        assertEquals(6, summary.path("topic_count").asInt(), "docs audit must project the current topic count");
        assertEquals(5, summary.path("workspace_enabled_topics").asInt(),
            "docs audit must project current upgraded workspace count");
        assertEquals(1, summary.path("readme_only_topics").asInt(),
            "docs audit must keep the remaining README-only topic visible");
        assertEquals(5, summary.path("subtopic_routing_coverage_count").asInt(),
            "docs audit must keep the business-topic subtopic-routing sections visible");
        assertEquals(5, summary.path("entry_advice_coverage_count").asInt(),
            "docs audit must keep the business-topic current-entry-advice sections visible");
        assertEquals(5, summary.path("subtopic_routing_table_coverage_count").asInt(),
            "docs audit must keep the business-topic subtopic-routing tables visible");
        assertEquals(5, summary.path("stable_baseline_coverage_count").asInt(),
            "docs audit must keep the business-topic stable-baseline sections visible");
        assertEquals(5, summary.path("stable_baseline_narrative_coverage_count").asInt(),
            "docs audit must keep the business-topic still-true baseline narratives visible");
        assertEquals(6, summary.path("topic_readme_order_coverage_count").asInt(),
            "docs audit must keep the stable topic README heading order visible for every topic");
        assertEquals(5, summary.path("progress_section_order_coverage_count").asInt(),
            "docs audit must keep the stable PROGRESS heading order visible for every upgraded topic");
        assertEquals(5, summary.path("current_mainline_grouping_coverage_count").asInt(),
            "docs audit must keep the business-topic current-mainline grouped subsections visible");
        assertEquals(4, summary.path("progress_lane_coverage_count").asInt(),
            "docs audit must keep the current theme-progress lanes visible for PROGRESS-enabled business topics");
        assertEquals(4, summary.path("runs_readme_entry_coverage_count").asInt(),
            "docs audit must keep runs/README.md explicitly reachable for every runs-enabled topic");
        assertEquals(4, summary.path("runs_reading_order_coverage_count").asInt(),
            "docs audit must keep runs/README.md inside the default reading order for every runs-enabled topic");
        assertEquals(4, summary.path("runs_readme_section_coverage_count").asInt(),
            "docs audit must keep the minimal runs/README.md structure visible for every runs-enabled topic");
        assertEquals(1, summary.path("readme_only_workspace_judgment_coverage_count").asInt(),
            "docs audit must keep the README-only workspace-judgment contract visible");
        assertEquals(1, summary.path("readme_only_upgrade_gate_coverage_count").asInt(),
            "docs audit must keep the README-only upgrade-gate contract visible");
        assertEquals(1, summary.path("readme_only_reading_order_coverage_count").asInt(),
            "docs audit must keep the README-only reading-order contract visible");
        assertEquals(5, payload.path("topic_states").findValuesAsText("progress").stream().filter("true"::equals).count(),
            "docs audit payload must keep the current PROGRESS-enabled topic count visible");
        assertTrue(summary.path("readme_navigation_heading_present").asBoolean(),
            "README.md must keep the public docs navigation section");
        assertTrue(summary.path("readme_docs_index_present").asBoolean(),
            "README.md must route readers to docs/README.md");
        assertTrue(summary.path("readme_meta_governance_present").asBoolean(),
            "README.md must route readers to docs/meta/README.md and docs/DOCS_GOVERNANCE.md");
        assertTrue(summary.path("readme_agent_entry_present").asBoolean(),
            "README.md must route agent readers to WAKE.md and AGENTS.md");
        assertTrue(summary.path("readme_state_decisions_present").asBoolean(),
            "README.md must route continuity reads to STATE.md and DECISIONS.md");
        assertTrue(summary.path("startup_boundary_heading_present").asBoolean(),
            "STARTUP_GUIDE.md must keep the boundary section");
        assertTrue(summary.path("startup_docs_redirect_present").asBoolean(),
            "STARTUP_GUIDE.md must route non-startup work to docs/README.md");
        assertTrue(summary.path("startup_agent_redirect_present").asBoolean(),
            "STARTUP_GUIDE.md must route agent work to WAKE.md and AGENTS.md");
        assertTrue(summary.path("startup_dialogue_redirect_present").asBoolean(),
            "STARTUP_GUIDE.md must route UI/browser work to docs/dialogue/README.md");
        assertTrue(summary.path("startup_provider_redirect_present").asBoolean(),
            "STARTUP_GUIDE.md must route provider/worker work to docs/provider/README.md");
        assertTrue(summary.path("startup_state_decisions_present").asBoolean(),
            "STARTUP_GUIDE.md must route continuity reads to STATE.md and DECISIONS.md");
        assertTrue(summary.path("docs_readme_role_heading_present").asBoolean(),
            "docs/README.md must keep the by-role entry section");
        assertTrue(summary.path("docs_readme_startup_role_entry_present").asBoolean(),
            "docs/README.md must keep the startup/verify role entry");
        assertTrue(summary.path("docs_readme_governance_role_entry_present").asBoolean(),
            "docs/README.md must keep the docs-governance role entry and its follow-up reads");
        assertTrue(summary.path("docs_readme_agent_role_entry_present").asBoolean(),
            "docs/README.md must keep the agent handoff role entry");
        assertTrue(summary.path("docs_readme_state_role_entry_present").asBoolean(),
            "docs/README.md must keep the continuity-read role entry");
        assertTrue(summary.path("docs_readme_meta_entry_present").asBoolean(),
            "docs/README.md must keep the meta topic entry");
        assertTrue(summary.path("docs_readme_governance_entry_present").asBoolean(),
            "docs/README.md must route governance reads to DOCS_GOVERNANCE.md");
        assertTrue(summary.path("docs_readme_audit_script_present").asBoolean(),
            "docs/README.md must keep the docs audit script entry");
        assertTrue(summary.path("docs_readme_focused_regression_present").asBoolean(),
            "docs/README.md must keep the focused docs regression command entry");
        assertTrue(summary.path("docs_governance_path_clarity_present").asBoolean(),
            "docs/DOCS_GOVERNANCE.md must keep file-local relative paths for root and governance entrypoints");
        assertTrue(summary.path("meta_writeback_chain_present").asBoolean(),
            "docs/meta/README.md must keep the default docs-governance writeback chain");
        assertTrue(summary.path("agents_start_guardrails_present").asBoolean(),
            "AGENTS.md must keep the start-work guardrails section");
        assertTrue(summary.path("agents_fact_map_present").asBoolean(),
            "AGENTS.md must keep the project-facts entry section");
        assertTrue(summary.path("agents_fact_baselines_present").asBoolean(),
            "AGENTS.md must route stable project facts to the dedicated docs baselines");
        assertTrue(summary.path("agents_project_overview_absent").asBoolean(),
            "AGENTS.md must not grow back into a project overview document");
        assertTrue(summary.path("agents_tech_stack_absent").asBoolean(),
            "AGENTS.md must not duplicate the project technical-stack baseline");
        assertTrue(summary.path("agents_source_tree_absent").asBoolean(),
            "AGENTS.md must not duplicate the source-tree baseline");
        assertTrue(summary.path("agents_api_cheatsheet_absent").asBoolean(),
            "AGENTS.md must not duplicate the API contracts baseline");
        assertEquals(6, summary.path("wake_workspace_row_coverage_count").asInt(),
            "WAKE.md workspace state block must cover every topic");
        assertEquals(6, summary.path("agents_workspace_row_coverage_count").asInt(),
            "AGENTS.md workspace state block must cover every topic");
        assertTrue(summary.path("wake_progress_reading_order_present").asBoolean(),
            "WAKE.md must keep the PROGRESS reading path");
        assertTrue(summary.path("agents_progress_reading_order_present").asBoolean(),
            "AGENTS.md must keep the PROGRESS reading path");
        assertTrue(summary.path("wake_readme_only_reading_order_present").asBoolean(),
            "WAKE.md must keep the README-only reading path");
        assertTrue(summary.path("agents_readme_only_reading_order_present").asBoolean(),
            "AGENTS.md must keep the README-only reading path");
        assertEquals(0, payload.path("violations").size(), "docs audit payload must not emit violations");
    }

    private ProcessResult runAuditScript() throws IOException, InterruptedException {
        Path repoRoot = Paths.get("").toAbsolutePath().normalize();
        Process process = new ProcessBuilder(List.of(
            "powershell",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            ".\\scripts\\Run-DocsIndexAudit.ps1",
            "-FailOnViolation"
        ))
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)
            .start();

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, stdout);
    }

    private boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record ProcessResult(int exitCode, String stdout) {
    }
}
