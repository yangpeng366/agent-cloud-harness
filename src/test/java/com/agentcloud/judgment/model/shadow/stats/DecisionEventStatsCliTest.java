package com.agentcloud.judgment.model.shadow.stats;

import com.agentcloud.judgment.model.shadow.DecisionEventSink;
import com.agentcloud.judgment.model.shadow.HeuristicRouterProvider;
import com.agentcloud.judgment.model.shadow.JsonlDecisionEventSink;
import com.agentcloud.judgment.model.shadow.RouterShadowService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionEventStatsCliTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final PrintStream origOut = System.out;
    private final PrintStream origErr = System.err;

    @BeforeEach
    void redirect() {
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
    }

    @AfterEach
    void restore() {
        System.setOut(origOut);
        System.setErr(origErr);
    }

    @Test
    void missingArgsExitsWithCode2() {
        int code = runCli(new String[]{});
        assertEquals(2, code);
        assertTrue(err.toString().contains("Usage"));
    }

    @Test
    void noPathArgExitsWithCode2() {
        int code = runCli(new String[]{"--pretty"});
        assertEquals(2, code);
    }

    @Test
    void emptyFileReturnsZeroEventsSummary(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("empty.jsonl");
        Files.writeString(jsonl, "");

        int code = runCli(new String[]{jsonl.toString()});
        assertEquals(0, code, "空文件应返回 0");
        String output = out.toString();
        JsonNode root = MAPPER.readTree(output);
        assertEquals(jsonl.toString(), root.get("path").asText());
        assertEquals(0L, root.get("summary").get("totalEvents").asLong());
    }

    @Test
    void populatedFileOutputsAggregatedSummary(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("mixed.jsonl");
        DecisionEventSink sink = new JsonlDecisionEventSink(jsonl, 16);
        RouterShadowService router = new RouterShadowService(new HeuristicRouterProvider(), sink);
        router.observe("run-A", "turn-1", "task-A", "worker:codex",
                Map.of("difficulty", 0.92, "high_stakes", true));
        router.observe("run-A", "turn-2", "task-A", "worker:codex",
                Map.of("difficulty", 0.10));

        int code = runCli(new String[]{"--pretty", jsonl.toString()});
        assertEquals(0, code);
        String output = out.toString();
        assertTrue(output.contains("\"path\""), "--pretty 应输出 JSON 含 path 字段");
        assertTrue(output.contains("\"summary\""));
        JsonNode root = MAPPER.readTree(output);
        JsonNode summary = root.get("summary");
        assertEquals(2L, summary.get("totalEvents").asLong());
        assertEquals(2L, summary.get("eventsByKind").get("router").asLong());
        assertEquals(1L, summary.get("actionsByKind").get("router:human_required").asLong());
        assertEquals(1L, summary.get("actionsByKind").get("router:fast").asLong());
    }

    @Test
    void missingFileReturnsZeroWithoutCrash() throws Exception {
        Path missing = Paths.get("Z:/definitely-missing-cli-test.jsonl");
        int code = runCli(new String[]{missing.toString()});
        assertEquals(0, code, "missing 文件不抛异常，应返回 0 events");
        String output = out.toString();
        JsonNode root = MAPPER.readTree(output);
        assertEquals(0L, root.get("summary").get("totalEvents").asLong());
    }

    @Test
    void reasonsFlagIncludesReasonSummary(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("reasons.jsonl");
        DecisionEventSink sink = new JsonlDecisionEventSink(jsonl, 16);
        RouterShadowService router = new RouterShadowService(new HeuristicRouterProvider(), sink);
        router.observe("run-A", "turn-1", "task-A", "worker:codex",
                Map.of("difficulty", 0.92, "high_stakes", true));
        router.observe("run-A", "turn-2", "task-A", "worker:openclaw",
                Map.of("difficulty", 0.10));

        int code = runCli(new String[]{"--reasons", jsonl.toString()});
        assertEquals(0, code);
        String output = out.toString();
        JsonNode root = MAPPER.readTree(output);
        assertEquals(2L, root.get("summary").get("totalEvents").asLong());
        assertTrue(root.has("reasons"), "--reasons 必须含 reasons 字段");
        assertEquals(2L, root.get("reasons").get("totalEvents").asLong());
        assertEquals(1L, root.get("reasons").get("reasonsByKind").get("router:human_or_high_stakes_flag_set").asLong());
        assertEquals(1L, root.get("reasons").get("reasonsByKind").get("router:difficulty_below_fast_max").asLong());
    }

    @Test
    void withoutReasonsFlagOmitsReasonSummary(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("noreasons.jsonl");
        Files.writeString(jsonl, "");

        int code = runCli(new String[]{jsonl.toString()});
        assertEquals(0, code);
        JsonNode root = MAPPER.readTree(out.toString());
        assertTrue(root.has("summary"));
        assertFalse(root.has("reasons"), "不带 --reasons 时不应含 reasons 字段");
    }

    private int runCli(String[] args) {
        return DecisionEventStatsCli.run(args, new PrintStream(out, true), new PrintStream(err, true));
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
}