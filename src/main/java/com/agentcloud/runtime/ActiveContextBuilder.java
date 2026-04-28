package com.agentcloud.runtime;

import com.agentcloud.model.Artifact;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.policy.ActiveContextPolicy;
import com.agentcloud.runtime.policy.ExclusionPolicy;
import com.agentcloud.runtime.policy.RetentionPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 runtime trace 组装当前活动工作面。
 */
public class ActiveContextBuilder {
    private final ActiveContextPolicy activeContextPolicy;
    private final RetentionPolicy retentionPolicy;
    private final ExclusionPolicy exclusionPolicy;

    public ActiveContextBuilder(ActiveContextPolicy activeContextPolicy,
                                RetentionPolicy retentionPolicy,
                                ExclusionPolicy exclusionPolicy) {
        this.activeContextPolicy = activeContextPolicy;
        this.retentionPolicy = retentionPolicy;
        this.exclusionPolicy = exclusionPolicy;
    }

    public ActiveContext build(Task task, ResumePacket packet, List<Event> events,
                               List<Decision> decisions, List<Artifact> artifacts) {
        return build(task, packet, null, events, decisions, artifacts, List.of());
    }

    public ActiveContext build(Task task, ResumePacket packet, Checkpoint checkpoint, List<Event> events,
                               List<Decision> decisions, List<Artifact> artifacts,
                               List<String> learnedHints) {
        return activeContextPolicy.build(task, packet, checkpoint, events, decisions, artifacts, learnedHints, retentionPolicy, exclusionPolicy);
    }

    public static class DefaultActiveContextPolicy implements ActiveContextPolicy {
        private static final int DEFAULT_BUDGET = 12;
        private static final int CONSTRAINT_PREVIEW_LIMIT = 220;

        @Override
        public ActiveContext build(Task task, ResumePacket packet, Checkpoint checkpoint, List<Event> events,
                                   List<Decision> decisions, List<Artifact> artifacts,
                                   List<String> learnedHints,
                                   RetentionPolicy retentionPolicy, ExclusionPolicy exclusionPolicy) {
            boolean terminalTask = isTerminal(task);
            List<String> constraints = collectConstraints(task, packet, checkpoint, terminalTask);
            List<String> keyEvents = mapEvents(events);
            List<String> keyDecisions = mapDecisions(decisions, checkpoint);
            List<String> keyArtifacts = mapArtifacts(artifacts, checkpoint);
            List<String> openQuestions = collectOpenQuestions(task, packet, checkpoint, decisions, terminalTask);
            List<String> nextCandidates = collectNextCandidates(task, packet, checkpoint, decisions, terminalTask);
            List<String> riskHints = collectRiskHints(checkpoint, decisions);
            List<String> retainedHints = learnedHints == null ? List.of() : learnedHints;

            constraints = exclusionPolicy.apply(retentionPolicy.apply(constraints, 3));
            keyEvents = exclusionPolicy.apply(retentionPolicy.apply(keyEvents, 3));
            keyDecisions = exclusionPolicy.apply(retentionPolicy.apply(keyDecisions, 3));
            keyArtifacts = exclusionPolicy.apply(retentionPolicy.apply(keyArtifacts, 3));
            openQuestions = exclusionPolicy.apply(retentionPolicy.apply(openQuestions, 2));
            nextCandidates = exclusionPolicy.apply(retentionPolicy.apply(nextCandidates, 2));
            riskHints = exclusionPolicy.apply(retentionPolicy.apply(riskHints, 2));
            retainedHints = exclusionPolicy.apply(retentionPolicy.apply(retainedHints, 2));

            String taskFocus = firstNonBlank(task.goal(), task.nextStep(), task.title());
            String continuitySummary = resolveContinuitySummary(task, packet, checkpoint, terminalTask);
            String continuitySource = resolveContinuitySource(task, packet, checkpoint, terminalTask);

            List<String> lines = new ArrayList<>();
            addLine(lines, "Task Focus", taskFocus);
            addLines(lines, "Constraints", constraints);
            addLines(lines, "Key Decisions", keyDecisions);
            addLines(lines, "Key Artifacts", keyArtifacts);
            addLines(lines, "Recent Events", keyEvents);
            addLines(lines, "Open Questions", openQuestions);
            addLines(lines, "Next Candidates", nextCandidates);
            addLines(lines, "Risk Hints", riskHints);
            addLines(lines, "Learned Hints", retainedHints);
            addLine(lines, "Continuity", continuitySummary);

            List<String> retained = retentionPolicy.apply(lines, DEFAULT_BUDGET);
            retained = exclusionPolicy.apply(retained);
            List<String> selectionTrace = buildSelectionTrace(
                constraints, keyEvents, keyDecisions, keyArtifacts, openQuestions, nextCandidates, riskHints, retainedHints,
                continuitySummary, continuitySource, retained, checkpoint, terminalTask
            );

            return new ActiveContext(
                taskFocus,
                constraints,
                keyEvents,
                keyDecisions,
                keyArtifacts,
                openQuestions,
                nextCandidates,
                riskHints,
                retainedHints,
                selectionTrace,
                continuitySummary,
                String.join("\n", retained),
                DEFAULT_BUDGET
            );
        }

        private List<String> buildSelectionTrace(List<String> constraints, List<String> keyEvents,
                                                 List<String> keyDecisions, List<String> keyArtifacts,
                                                 List<String> openQuestions, List<String> nextCandidates,
                                                 List<String> riskHints, List<String> retainedHints,
                                                 String continuitySummary, String continuitySource, List<String> retained,
                                                 Checkpoint checkpoint, boolean terminalTask) {
            List<String> trace = new ArrayList<>();
            trace.add("budget=" + DEFAULT_BUDGET + ", retained_lines=" + retained.size());
            trace.add("constraints=" + constraints.size() + ", events=" + keyEvents.size()
                + ", decisions=" + keyDecisions.size() + ", artifacts=" + keyArtifacts.size());
            if (terminalTask) {
                trace.add("terminal task trimming skipped open questions and next candidates");
            }
            if (!openQuestions.isEmpty()) {
                trace.add("open_questions retained because next-step ambiguity or unresolved rationale exists");
            }
            if (!nextCandidates.isEmpty()) {
                trace.add("next_candidates retained as likely follow-up actions: " + nextCandidates.size());
            }
            if (!riskHints.isEmpty()) {
                trace.add("risk_hints retained from checkpoint or recent decision rationale: " + riskHints.size());
            }
            if (!retainedHints.isEmpty()) {
                trace.add("learned_hints included from operational learning memory: " + retainedHints.size());
            }
            if (continuitySummary != null && !continuitySummary.isBlank()) {
                trace.add("continuity summary sourced from " + continuitySource);
            }
            if (checkpoint != null) {
                trace.add("checkpoint context sourced from latest consolidation checkpoint: " + checkpoint.checkpointType());
            }
            if (!keyDecisions.isEmpty()) {
                trace.add("decision summaries retained from recent decision trace");
            }
            if (!keyArtifacts.isEmpty()) {
                trace.add("artifact summaries retained from recent artifact trace");
            }
            if (!keyEvents.isEmpty()) {
                trace.add("event summaries retained from recent event trace");
            }
            return trace;
        }

        private List<String> collectConstraints(Task task, ResumePacket packet, Checkpoint checkpoint, boolean terminalTask) {
            List<String> items = new ArrayList<>();
            if (task.priority() != null && !task.priority().isBlank()) {
                items.add("priority=" + task.priority());
            }
            String assignedWorker = firstNonBlank(
                task.assignedWorker(),
                packet != null ? packet.assignedWorker() : null
            );
            if (assignedWorker != null && !assignedWorker.isBlank()) {
                items.add("assigned_worker=" + assignedWorker);
            }
            if (task.waitingReason() != null && !task.waitingReason().isBlank()) {
                items.add("waiting_reason=" + task.waitingReason());
            }
            if (packet != null && packet.currentStatus() != null && !packet.currentStatus().isBlank()) {
                items.add("packet_status=" + packet.currentStatus());
            }
            if (packet != null && packet.currentNode() != null && !packet.currentNode().isBlank()) {
                items.add("packet_node=" + packet.currentNode());
            }
            if (task.metadata() != null) {
                Object intent = task.metadata().get("intent");
                if (intent != null && !intent.toString().isBlank()) {
                    items.add("intent=" + previewConstraintValue(intent.toString(), CONSTRAINT_PREVIEW_LIMIT));
                }
                Object taskType = task.metadata().get("task_type");
                if (taskType != null && !taskType.toString().isBlank()) {
                    items.add("task_type=" + taskType);
                }
            }
            if (!terminalTask && packet != null && packet.nextStep() != null && !packet.nextStep().isBlank()) {
                items.add("packet_next_step=" + packet.nextStep());
            }
            if (checkpoint != null && checkpoint.checkpointType() != null && !checkpoint.checkpointType().isBlank()) {
                items.add("latest_checkpoint=" + checkpoint.checkpointType());
            }
            return items;
        }

        private List<String> collectOpenQuestions(Task task, ResumePacket packet, Checkpoint checkpoint, List<Decision> decisions,
                                                  boolean terminalTask) {
            if (terminalTask) {
                return List.of();
            }
            List<String> items = new ArrayList<>();
            if (task.nextStep() == null || task.nextStep().isBlank()) {
                items.add("next_step_not_yet_clear");
            }
            if (packet != null && packet.openQuestions() != null) {
                items.addAll(packet.openQuestions());
            }
            if (packet != null && packet.nextStep() != null && !packet.nextStep().isBlank()) {
                items.add(packet.nextStep());
            }
            items.addAll(readStringList(checkpoint, "open_questions"));
            decisions.stream()
                .map(Decision::rationale)
                .filter(this::looksOpenQuestion)
                .limit(2)
                .forEach(items::add);
            return items;
        }

        private List<String> collectNextCandidates(Task task, ResumePacket packet, Checkpoint checkpoint, List<Decision> decisions,
                                                   boolean terminalTask) {
            if (terminalTask) {
                return List.of();
            }
            List<String> items = new ArrayList<>(readStringList(checkpoint, "next_candidates"));
            if (packet != null && packet.nextStep() != null && !packet.nextStep().isBlank()) {
                items.add(packet.nextStep());
            }
            if (task.nextStep() != null && !task.nextStep().isBlank()) {
                items.add(task.nextStep());
            }
            decisions.stream()
                .map(this::decisionNextCandidate)
                .filter(value -> value != null && !value.isBlank())
                .limit(2)
                .forEach(items::add);
            return items;
        }

        private String resolveContinuitySummary(Task task, ResumePacket packet, Checkpoint checkpoint, boolean terminalTask) {
            if (terminalTask) {
                return firstNonBlank(
                    task.summary(),
                    packet != null ? packet.activeTaskSummary() : null,
                    packet != null ? packet.decisionSummary() : null,
                    packet != null ? packet.artifactSummary() : null,
                    checkpoint != null ? checkpoint.consolidationSummary() : null,
                    task.goal(),
                    task.title()
                );
            }
            return firstNonBlank(
                packet != null ? packet.latestSummary() : null,
                packet != null ? packet.activeTaskSummary() : null,
                packet != null ? packet.decisionSummary() : null,
                packet != null ? packet.artifactSummary() : null,
                checkpoint != null ? checkpoint.consolidationSummary() : null,
                task.summary()
            );
        }

        private String resolveContinuitySource(Task task, ResumePacket packet, Checkpoint checkpoint, boolean terminalTask) {
            if (terminalTask && task.summary() != null && !task.summary().isBlank()) {
                return "task summary";
            }
            if (packet != null && packet.latestSummary() != null && !packet.latestSummary().isBlank()) {
                return "latest resume packet latest_summary";
            }
            if (packet != null && packet.activeTaskSummary() != null && !packet.activeTaskSummary().isBlank()) {
                return "latest resume packet active_task_summary";
            }
            if (packet != null && packet.decisionSummary() != null && !packet.decisionSummary().isBlank()) {
                return "latest resume packet decision_summary";
            }
            if (packet != null && packet.artifactSummary() != null && !packet.artifactSummary().isBlank()) {
                return "latest resume packet artifact_summary";
            }
            if (checkpoint != null && checkpoint.consolidationSummary() != null && !checkpoint.consolidationSummary().isBlank()) {
                return "latest consolidation checkpoint";
            }
            if (task.summary() != null && !task.summary().isBlank()) {
                return "task summary";
            }
            if (task.goal() != null && !task.goal().isBlank()) {
                return "task goal";
            }
            return "task title";
        }

        private boolean isTerminal(Task task) {
            if (task == null || task.status() == null) {
                return false;
            }
            return List.of("done", "failed").contains(task.status().toLowerCase());
        }

        private List<String> collectRiskHints(Checkpoint checkpoint, List<Decision> decisions) {
            List<String> items = new ArrayList<>(readStringList(checkpoint, "repeated_failure_hints"));
            decisions.stream()
                .map(Decision::rationale)
                .filter(this::looksRiskHint)
                .limit(2)
                .forEach(items::add);
            return items;
        }

        private List<String> mapEvents(List<Event> events) {
            return events.stream()
                .map(e -> "[" + e.eventType() + "] " + safeText(e.summary()))
                .toList();
        }

        private List<String> mapDecisions(List<Decision> decisions, Checkpoint checkpoint) {
            List<String> items = new ArrayList<>(readStringList(checkpoint, "key_decisions"));
            items.addAll(decisions.stream()
                .map(d -> safeText(d.summary()))
                .toList());
            return items;
        }

        private List<String> mapArtifacts(List<Artifact> artifacts, Checkpoint checkpoint) {
            List<String> items = new ArrayList<>(readStringList(checkpoint, "key_artifacts"));
            items.addAll(artifacts.stream()
                .map(a -> {
                    String title = a.title() != null && !a.title().isBlank() ? a.title() : "artifact";
                    String summary = safeText(a.summary());
                    return summary.isBlank() ? title : title + ": " + summary;
                })
                .toList());
            return items;
        }

        private List<String> readStringList(Checkpoint checkpoint, String key) {
            if (checkpoint == null || checkpoint.refinedPacket() == null) {
                return List.of();
            }
            Object raw = checkpoint.refinedPacket().get(key);
            if (!(raw instanceof List<?> values)) {
                return List.of();
            }
            return values.stream()
                .filter(value -> value != null && !value.toString().isBlank())
                .map(Object::toString)
                .toList();
        }

        private String decisionNextCandidate(Decision decision) {
            if (decision == null || decision.metadata() == null) {
                return null;
            }
            Object nextStep = decision.metadata().get("next_step");
            if (nextStep != null && !nextStep.toString().isBlank()) {
                return nextStep.toString();
            }
            Object suggested = decision.metadata().get("suggested_next_action");
            if (suggested != null && !suggested.toString().isBlank()) {
                return suggested.toString();
            }
            return null;
        }

        private boolean looksOpenQuestion(String value) {
            return value != null && !value.isBlank() && (
                value.contains("?") || value.toLowerCase().contains("need") || value.toLowerCase().contains("clar")
            );
        }

        private boolean looksRiskHint(String value) {
            if (value == null || value.isBlank()) {
                return false;
            }
            String normalized = value.toLowerCase();
            return normalized.contains("fail") || normalized.contains("error")
                || normalized.contains("misalign") || normalized.contains("blocked");
        }

        private String safeText(String value) {
            return value == null ? "" : value.trim();
        }

        private String previewConstraintValue(String value, int maxLength) {
            if (value == null) {
                return "";
            }
            String normalized = value
                .replaceAll("\\s+", " ")
                .replace('：', ':')
                .trim();
            if (normalized.length() <= maxLength) {
                return normalized;
            }
            return normalized.substring(0, maxLength).trim() + "...";
        }

        private String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return "";
        }

        private void addLine(List<String> lines, String label, String value) {
            if (value != null && !value.isBlank()) {
                lines.add(label + ": " + value);
            }
        }

        private void addLines(List<String> lines, String label, List<String> values) {
            if (values == null || values.isEmpty()) {
                return;
            }
            lines.add(label + ":");
            values.forEach(value -> lines.add("- " + value));
        }
    }

    public static class DefaultRetentionPolicy implements RetentionPolicy {
        @Override
        public List<String> apply(List<String> items, int limit) {
            if (items == null || items.isEmpty() || limit <= 0) {
                return List.of();
            }
            return items.stream().limit(limit).toList();
        }
    }

    public static class DefaultExclusionPolicy implements ExclusionPolicy {
        @Override
        public List<String> apply(List<String> items) {
            if (items == null || items.isEmpty()) {
                return List.of();
            }
            return items.stream()
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
        }
    }
}
