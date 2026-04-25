package com.agentcloud.runtime;

import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Task;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveContextBuilderTerminalTaskTest {

    @Test
    void doneTaskSkipsHistoricalOpenQuestionsAndNextCandidates() {
        ActiveContextBuilder builder = new ActiveContextBuilder(
            new ActiveContextBuilder.DefaultActiveContextPolicy(),
            new ActiveContextBuilder.DefaultRetentionPolicy(),
            new ActiveContextBuilder.DefaultExclusionPolicy()
        );

        Task task = Task.create("task_1", "session_1", "continuity article", "active", "high")
            .withSummary("终稿已完成并可直接发布")
            .withStatus("done")
            .withControlNode("end")
            .withNextStep(null)
            .withCompletedAt(Instant.parse("2026-04-24T12:20:00Z"));
        Checkpoint checkpoint = new Checkpoint(
            "checkpoint_1",
            "session_1",
            "task_1",
            Instant.parse("2026-04-24T12:10:00Z"),
            "periodic",
            "旧的中间收敛摘要",
            Map.of(
                "open_questions", List.of("是否还需要补一段导语"),
                "next_candidates", List.of("补写结尾金句")
            ),
            Map.of(),
            Map.of()
        );
        Decision decision = new Decision(
            "decision_1",
            "session_1",
            "task_1",
            Instant.parse("2026-04-24T12:11:00Z"),
            "judgment",
            "终稿阶段",
            "Need another pass?",
            "high",
            null,
            Map.of("next_step", "再润色一次开头")
        );

        ActiveContext activeContext = builder.build(
            task,
            null,
            checkpoint,
            List.of(),
            List.of(decision),
            List.of(),
            List.of("保留观点对比的锋利度")
        );

        assertEquals(List.of(), activeContext.openQuestions());
        assertEquals(List.of(), activeContext.nextCandidates());
        assertEquals("终稿已完成并可直接发布", activeContext.continuitySummary());
        assertTrue(activeContext.selectionTrace().stream()
            .anyMatch(item -> item.contains("terminal task trimming skipped open questions and next candidates")));
        assertFalse(activeContext.synthesizedContext().contains("next_step_not_yet_clear"));
    }

    @Test
    void longIntentConstraintIsCompactedIntoSinglePreviewLine() {
        ActiveContextBuilder builder = new ActiveContextBuilder(
            new ActiveContextBuilder.DefaultActiveContextPolicy(),
            new ActiveContextBuilder.DefaultRetentionPolicy(),
            new ActiveContextBuilder.DefaultExclusionPolicy()
        );

        String longIntent = """
            Write a Chinese WeChat article around continuity versus autonomy.
            Round 1 should only produce outline and argument structure.
            Round 2 should expand into a first draft.
            Round 3 should polish into final copy.

            Reference material:
            continuity matters because resume, handoff, packet, audit, and checkpoint all need to stay coherent across sessions and workers.
            Keep elaborating with examples from multi-round execution and agent infrastructure until this line is definitely longer than the runtime preview budget.
            """;
        Task task = new Task(
            "task_2",
            "session_2",
            null,
            "continuity article",
            "active",
            "high",
            Instant.parse("2026-04-24T12:00:00Z"),
            Instant.parse("2026-04-24T12:00:00Z"),
            null,
            null,
            null,
            null,
            "Write article",
            null,
            "kimi",
            "scheduler",
            null,
            Map.of(
                "task_type", "research",
                "intent", longIntent
            )
        );

        ActiveContext activeContext = builder.build(
            task,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );

        String intentConstraint = activeContext.constraints().stream()
            .filter(item -> item.startsWith("intent="))
            .findFirst()
            .orElse(null);

        assertNotNull(intentConstraint);
        assertFalse(intentConstraint.contains("\n"));
        assertTrue(intentConstraint.endsWith("..."));
        assertTrue(intentConstraint.length() < 240);
        assertFalse(activeContext.synthesizedContext().contains("Keep elaborating with examples from multi-round execution"));
    }
}
