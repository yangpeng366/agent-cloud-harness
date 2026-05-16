package com.agentcloud.engine;

import com.agentcloud.model.Session;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.SessionMessageCreateRequest;
import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;
import com.agentcloud.model.openai.ChatCompletionRequest;
import com.agentcloud.model.openai.ChatCompletionResponse;
import com.agentcloud.model.openai.ChatMessage;
import com.agentcloud.model.openai.ModelCard;
import com.agentcloud.model.openai.ModelListResponse;
import com.agentcloud.model.openai.ResponseCreateRequest;
import com.agentcloud.model.openai.ResponsesCreateResponse;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public class ChatFacadeService {
    private static final String DEFAULT_MODEL = "agentcloud-default";
    private static final List<String> SUPPORTED_MODELS = List.of(
        DEFAULT_MODEL,
        "agentcloud-strong",
        "agentcloud-fast"
    );
    private static final String CHAT_COMPLETION_PATH = "/v1/chat/completions";
    private static final String RESPONSES_PATH = "/v1/responses";
    private final SessionService sessionService;
    private final TaskService taskService;

    public ChatFacadeService(SessionService sessionService, TaskService taskService) {
        this.sessionService = sessionService;
        this.taskService = taskService;
    }

    public ChatCompletionResponse createCompletion(ChatCompletionRequest request) {
        return createCompletion(request, CHAT_COMPLETION_PATH);
    }

    private ChatCompletionResponse createCompletion(ChatCompletionRequest request, String requestPath) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String facadeModel = normalizeModel(request.model());
        String lastUserTurn = extractLastUserTurn(request.messages());
        Map<String, Object> metadata = request.metadata() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(request.metadata());
        String taskMode = normalizeTaskMode(stringValue(metadata.get("task_mode")));
        String taskId = blankToNull(stringValue(metadata.get("task_id")));
        boolean autoStart = booleanValue(metadata.get("auto_start"), true);

        Task referencedTask = resolveReferencedTask(taskId);
        String sessionId = resolveSessionId(metadata, referencedTask);
        Session session = sessionId == null
            ? sessionService.createSession(deriveSessionTitle(lastUserTurn), requestMetadata(requestPath))
            : requireSession(sessionId);

        if (referencedTask != null) {
            addUserTurn(
                session.id(),
                referencedTask.id(),
                "task_note",
                lastUserTurn,
                facadeModel,
                taskMode,
                metadata,
                requestPath
            );
            if ("message_only".equals(taskMode)) {
                return replyWithAck(facadeModel, session, referencedTask, "已记录到当前任务上下文。", requestPath);
            }
            if (!autoStart) {
                return replyWithAck(facadeModel, session, referencedTask, "已记录到当前任务上下文，等待手动继续。", requestPath);
            }
            taskService.continueTask(referencedTask.id(), requestMetadata(requestPath));
            return buildTaskCompletion(facadeModel, requireTask(referencedTask.id()), session.id());
        }

        Task activeTask = "task_auto".equals(taskMode) ? resolveActiveSessionTask(session) : null;
        if (activeTask != null) {
            addUserTurn(
                session.id(),
                activeTask.id(),
                "task_note",
                lastUserTurn,
                facadeModel,
                taskMode,
                metadata,
                requestPath
            );
            if (!autoStart) {
                return replyWithAck(facadeModel, session, activeTask, "已记录到当前任务上下文，等待手动继续。", requestPath);
            }
            taskService.continueTask(activeTask.id(), requestMetadata(requestPath));
            return buildTaskCompletion(facadeModel, requireTask(activeTask.id()), session.id());
        }

        String parentTaskId = blankToNull(stringValue(metadata.get("parent_task_id")));
        SessionMessage stagedUserTurn = addUserTurn(
            session.id(),
            null,
            "message_only".equals(taskMode)
                ? "user_note"
                : parentTaskId != null ? "task_followup" : "task_brief",
            lastUserTurn,
            facadeModel,
            taskMode,
            metadata,
            requestPath
        );
        if ("message_only".equals(taskMode)) {
            return replyWithAck(
                facadeModel,
                session,
                null,
                "已记录到当前会话。如需进入 harness 执行，请使用 task_auto 或 task_required。",
                requestPath
            );
        }

        String resolvedTaskType = resolveTaskType(metadata, lastUserTurn);
        TaskCreateRequest taskRequest = new TaskCreateRequest(
            firstNonBlank(blankToNull(stringValue(metadata.get("title"))), deriveTaskTitle(lastUserTurn)),
            resolvedTaskType,
            "user",
            firstNonBlank(blankToNull(stringValue(metadata.get("priority"))), "high"),
            lastUserTurn,
            firstNonBlank(blankToNull(stringValue(metadata.get("goal"))), lastUserTurn),
            parentTaskId,
            session.id(),
            buildTaskMetadata(metadata, facadeModel),
            autoStart
        );
        Task task = taskService.createTask(taskRequest, requestMetadata(requestPath));
        backfillTaskBinding(session.id(), stagedUserTurn, task);
        return buildTaskCompletion(facadeModel, requireTask(task.id()), session.id());
    }

    public ResponsesCreateResponse createResponse(ResponseCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        ChatCompletionResponse completion = createCompletion(
            new ChatCompletionRequest(
                request.model(),
                responseInputToMessages(request.input(), request.instructions()),
                request.stream(),
                request.metadata()
            ),
            RESPONSES_PATH
        );
        return toResponsesResponse(completion, request.previousResponseId());
    }

    public ModelListResponse listModels() {
        long created = Instant.now().getEpochSecond();
        return new ModelListResponse(
            "list",
            SUPPORTED_MODELS.stream()
                .map(model -> new ModelCard(model, "model", created, "agentcloud"))
                .toList()
        );
    }

    public ResponsesCreateResponse toResponsesResponse(ChatCompletionResponse completion, String previousResponseId) {
        String content = completion == null
            || completion.choices() == null
            || completion.choices().isEmpty()
            || completion.choices().get(0) == null
            || completion.choices().get(0).message() == null
            ? ""
            : firstNonBlank(blankToNull(completion.choices().get(0).message().content()), "");
        long createdAt = completion != null && completion.created() != null
            ? completion.created()
            : Instant.now().getEpochSecond();
        String responseId = completion != null && completion.id() != null
            ? completion.id().replaceFirst("^chatcmpl_", "resp_")
            : IdGenerator.newId("resp");
        String outputItemId = IdGenerator.newId("msg");
        ResponsesCreateResponse.OutputContent outputContent = new ResponsesCreateResponse.OutputContent(
            "output_text",
            content,
            List.of()
        );
        ResponsesCreateResponse.OutputItem outputItem = new ResponsesCreateResponse.OutputItem(
            outputItemId,
            "message",
            "completed",
            "assistant",
            List.of(outputContent)
        );
        return new ResponsesCreateResponse(
            responseId,
            "response",
            createdAt,
            "completed",
            createdAt,
            completion != null ? completion.model() : DEFAULT_MODEL,
            List.of(outputItem),
            content,
            new ResponsesCreateResponse.Usage(0, 0, 0),
            blankToNull(previousResponseId),
            completion != null ? completion.agentcloud() : null
        );
    }

    private ChatCompletionResponse replyWithAck(String facadeModel,
                                                Session session,
                                                Task task,
                                                String content,
                                                String requestPath) {
        SessionMessage message = sessionService.addMessage(
            session.id(),
            new SessionMessageCreateRequest(
                "assistant",
                "chat_reply",
                content,
                task != null ? task.id() : null,
                assistantReplyMetadata(facadeModel, task, requestPath)
            )
        );
        return buildCompletionResponse(
            facadeModel,
            content,
            session.id(),
            task,
            message
        );
    }

    private ChatCompletionResponse buildTaskCompletion(String facadeModel, Task task, String sessionId) {
        SessionMessage latestAssistant = latestAssistantTaskMessage(sessionId, task.id());
        String content = latestAssistant != null
            ? assistantContent(latestAssistant)
            : fallbackTaskContent(task);
        return buildCompletionResponse(facadeModel, content, sessionId, task, latestAssistant);
    }

    private ChatCompletionResponse buildCompletionResponse(String facadeModel,
                                                           String content,
                                                           String sessionId,
                                                           Task task,
                                                           SessionMessage latestAssistant) {
        long created = latestAssistant != null && latestAssistant.createdAt() != null
            ? latestAssistant.createdAt().getEpochSecond()
            : Instant.now().getEpochSecond();
        return new ChatCompletionResponse(
            IdGenerator.newId("chatcmpl"),
            "chat.completion",
            created,
            facadeModel,
            List.of(new ChatCompletionResponse.Choice(
                0,
                new ChatMessage("assistant", content),
                "stop"
            )),
            new ChatCompletionResponse.Usage(0, 0, 0),
            new ChatCompletionResponse.AgentCloudExtension(
                sessionId,
                task != null ? task.id() : null,
                task != null ? task.status() : null,
                task != null ? task.controlNode() : null,
                replyType(latestAssistant, task),
                replySource(latestAssistant, task),
                task != null ? "/api/v1/tasks/" + task.id() + "/live_flow" : null,
                task != null ? "/api/v1/tasks/" + task.id() + "/packet" : null
            )
        );
    }

    private SessionMessage latestAssistantTaskMessage(String sessionId, String taskId) {
        List<SessionMessage> messages = sessionService.listMessages(sessionId, 20, taskId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            SessionMessage message = messages.get(i);
            if (message == null || message.role() == null || message.content() == null) {
                continue;
            }
            String role = message.role();
            if (!"assistant".equalsIgnoreCase(role) && !"system".equalsIgnoreCase(role)) {
                continue;
            }
            return message;
        }
        return null;
    }

    private String assistantContent(SessionMessage message) {
        if (message == null) {
            return null;
        }
        String summaryPreview = sanitizeReadableFacadeSummary(
            metadataString(message.metadata(), "summary_preview"),
            message.metadata(),
            null
        );
        String nextStep = metadataString(message.metadata(), "next_step");
        if (summaryPreview != null) {
            return nextStep == null
                ? summaryPreview
                : summaryPreview + "\n\n下一步：" + nextStep;
        }
        return firstNonBlank(
            sanitizeReadableFacadeSummary(blankToNull(message.content()), message.metadata(), null),
            "任务已进入 harness。"
        );
    }

    private String fallbackTaskContent(Task task) {
        if (task == null) {
            return "当前会话已记录最新输入。";
        }
        String summary = sanitizeReadableFacadeSummary(blankToNull(task.summary()), task.metadata(), task.assignedWorker());
        String nextStep = blankToNull(task.nextStep());
        if (summary != null && nextStep != null) {
            return summary + "\n\n下一步：" + nextStep;
        }
        return firstNonBlank(summary, nextStep, "任务已进入 harness。");
    }

    private String sanitizeReadableFacadeSummary(String text, Map<String, Object> metadata, String assignedWorker) {
        String normalized = blankToNull(text);
        if (normalized == null || !looksLikeUnreadableWorkerOutput(normalized) || !isFailureMetadata(metadata)) {
            return normalized;
        }
        String worker = firstNonBlank(
            metadataString(metadata, "selected_worker"),
            metadataString(metadata, "assigned_worker"),
            assignedWorker
        );
        return worker == null
            ? "当前 worker 返回了不可读错误输出；请查看 details / live_flow。"
            : "worker " + worker + " 返回了不可读错误输出；请查看 details / live_flow。";
    }

    private boolean isFailureMetadata(Map<String, Object> metadata) {
        String status = firstNonBlank(
            metadataString(metadata, "execution_status"),
            metadataString(metadata, "worker_execution_status"),
            metadataString(metadata, "completion_status")
        );
        if (status == null) {
            return false;
        }
        return List.of("failed", "error", "timeout", "cancelled").contains(status.toLowerCase());
    }

    private boolean looksLikeUnreadableWorkerOutput(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return false;
        }
        long replacementCount = normalized.chars().filter(ch -> ch == '\uFFFD').count();
        return replacementCount >= 2 || normalized.contains("����") || (normalized.contains("û") && normalized.contains("��"));
    }

    private String replyType(SessionMessage message, Task task) {
        if (message != null) {
            return blankToNull(message.messageType());
        }
        return task == null ? "chat_reply" : "task_state";
    }

    private String replySource(SessionMessage message, Task task) {
        String messageType = replyType(message, task);
        if (messageType == null) {
            return task == null ? "session_ack" : "task_state";
        }
        return switch (messageType) {
            case "chat_reply" -> "session_ack";
            case "task_receipt" -> "task_receipt";
            case "task_progress" -> "task_progress";
            case "task_result" -> "task_result";
            case "task_action" -> "task_action";
            case "task_state" -> "task_state";
            default -> task == null ? "session_ack" : "task_state";
        };
    }

    private Map<String, Object> assistantReplyMetadata(String facadeModel, Task task, String requestPath) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source_surface", "chat_facade");
        metadata.put("created_via", "chat_facade");
        metadata.put("chat_completion_model", facadeModel);
        metadata.put("request_path", requestPath);
        if (task != null) {
            metadata.put("task_status", task.status());
            metadata.put("control_node", task.controlNode());
        }
        return metadata;
    }

    private SessionMessage addUserTurn(String sessionId,
                                       String taskId,
                                       String messageType,
                                       String content,
                                       String facadeModel,
                                       String taskMode,
                                       Map<String, Object> requestMetadata,
                                       String requestPath) {
        return sessionService.addMessage(
            sessionId,
            new SessionMessageCreateRequest(
                "user",
                messageType,
                content,
                taskId,
                userTurnMetadata(facadeModel, taskMode, requestMetadata, requestPath)
            )
        );
    }

    private void backfillTaskBinding(String sessionId, SessionMessage stagedUserTurn, Task task) {
        if (stagedUserTurn == null || task == null || task.id() == null) {
            return;
        }
        if (stagedUserTurn.taskId() != null) {
            return;
        }
        String messageType = blankToNull(stagedUserTurn.messageType());
        if (!"task_brief".equals(messageType) && !"task_followup".equals(messageType)) {
            return;
        }
        sessionService.bindMessageToTask(sessionId, stagedUserTurn.id(), task.id());
    }

    private Map<String, Object> userTurnMetadata(String facadeModel,
                                                 String taskMode,
                                                 Map<String, Object> requestMetadata,
                                                 String requestPath) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source_surface", "chat_facade");
        metadata.put("created_via", "chat_facade");
        metadata.put("chat_completion_model", facadeModel);
        metadata.put("task_mode", taskMode);
        metadata.put("request_path", requestPath);
        copyIfPresent(requestMetadata, metadata, "title");
        copyIfPresent(requestMetadata, metadata, "goal");
        copyIfPresent(requestMetadata, metadata, "task_type");
        copyIfPresent(requestMetadata, metadata, "priority");
        copyIfPresent(requestMetadata, metadata, "parent_task_id");
        copyIfPresent(requestMetadata, metadata, "assigned_worker");
        copyIfPresent(requestMetadata, metadata, "model_mode");
        if (requestMetadata != null && requestMetadata.containsKey("auto_start")) {
            metadata.put("auto_start", booleanValue(requestMetadata.get("auto_start"), true));
        }
        return metadata;
    }

    private Map<String, Object> buildTaskMetadata(Map<String, Object> requestMetadata, String facadeModel) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source_surface", "chat_facade");
        metadata.put("created_via", "chat_facade");
        metadata.put("chat_completion_model", facadeModel);
        metadata.put("model_mode", resolveModelMode(facadeModel, requestMetadata));
        putIfNotBlank(metadata, "task_type", blankToNull(stringValue(requestMetadata.get("task_type"))));
        copyIfPresent(requestMetadata, metadata, "assigned_worker");
        copyIfPresent(requestMetadata, metadata, "target_worker");
        copyIfPresent(requestMetadata, metadata, "preferred_worker");
        copyIfPresent(requestMetadata, metadata, "task_case_key");
        copyIfPresent(requestMetadata, metadata, "task_length_bucket");
        copyIfPresent(requestMetadata, metadata, "experiment_name");
        return metadata;
    }

    private String resolveTaskType(Map<String, Object> metadata, String lastUserTurn) {
        return TaskTypeHeuristics.effectiveTaskType(metadata, "continuation", lastUserTurn);
    }

    private String resolveModelMode(String facadeModel, Map<String, Object> metadata) {
        String explicit = blankToNull(stringValue(metadata.get("model_mode")));
        if (explicit != null) {
            return switch (explicit) {
                case "strong_only", "small_only", "orchestrated" -> explicit;
                default -> "orchestrated";
            };
        }
        return switch (facadeModel) {
            case "agentcloud-strong" -> "strong_only";
            case "agentcloud-fast" -> "small_only";
            default -> "orchestrated";
        };
    }

    private Task resolveActiveSessionTask(Session session) {
        if (session == null) {
            return null;
        }
        String currentTaskId = blankToNull(session.currentTaskId());
        if (currentTaskId == null) {
            return null;
        }
        Task task = taskService.getTask(currentTaskId);
        if (task == null || isTerminal(task.status())) {
            return null;
        }
        return task;
    }

    private boolean isTerminal(String status) {
        return "done".equalsIgnoreCase(blankToNull(status))
            || "failed".equalsIgnoreCase(blankToNull(status));
    }

    private Task resolveReferencedTask(String taskId) {
        if (taskId == null) {
            return null;
        }
        return requireTask(taskId);
    }

    private Task requireTask(String taskId) {
        Task task = taskService.getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("task not found");
        }
        return task;
    }

    private Session requireSession(String sessionId) {
        Session session = sessionService.getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("session not found");
        }
        return session;
    }

    private String resolveSessionId(Map<String, Object> metadata, Task referencedTask) {
        String requestedSessionId = blankToNull(stringValue(metadata.get("session_id")));
        if (referencedTask == null) {
            return requestedSessionId;
        }
        if (requestedSessionId != null && !requestedSessionId.equals(referencedTask.sessionId())) {
            throw new IllegalArgumentException("task must belong to the same session");
        }
        return referencedTask.sessionId();
    }

    private String deriveSessionTitle(String userTurn) {
        return deriveTitle(userTurn, 36, "chat session");
    }

    private String deriveTaskTitle(String userTurn) {
        return deriveTitle(userTurn, 48, "chat task");
    }

    private String deriveTitle(String text, int maxLength, String fallback) {
        String normalized = blankToNull(text);
        if (normalized == null) {
            return fallback;
        }
        String singleLine = normalized.replace('\r', ' ').replace('\n', ' ').trim();
        if (singleLine.length() <= maxLength) {
            return singleLine;
        }
        return singleLine.substring(0, maxLength) + "...";
    }

    private String normalizeModel(String raw) {
        String model = blankToNull(raw);
        if (model == null) {
            return DEFAULT_MODEL;
        }
        if (!SUPPORTED_MODELS.contains(model)) {
            throw new IllegalArgumentException("unsupported chat facade model");
        }
        return model;
    }

    private String normalizeTaskMode(String raw) {
        String normalized = blankToNull(raw);
        if (normalized == null) {
            return "task_auto";
        }
        return switch (normalized) {
            case "message_only", "task_auto", "task_required", "auto" -> "auto".equals(normalized) ? "task_auto" : normalized;
            default -> throw new IllegalArgumentException("unsupported task_mode");
        };
    }

    private String extractLastUserTurn(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null) {
                continue;
            }
            if (!"user".equalsIgnoreCase(blankToNull(message.role()))) {
                continue;
            }
            String content = blankToNull(message.content());
            if (content != null) {
                return content;
            }
        }
        throw new IllegalArgumentException("at least one user message with content is required");
    }

    private Map<String, Object> requestMetadata(String requestPath) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requested_via", "chat_facade");
        metadata.put("request_method", "POST");
        metadata.put("request_path", requestPath);
        metadata.put("openai_compatible", true);
        return metadata;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        return blankToNull(stringValue(metadata.get(key)));
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private void putIfNotBlank(Map<String, Object> metadata, String key, String value) {
        if (metadata == null || blankToNull(key) == null || blankToNull(value) == null) {
            return;
        }
        metadata.put(key, value);
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String normalized = blankToNull(stringValue(value));
        if (normalized == null) {
            return fallback;
        }
        return Boolean.parseBoolean(normalized);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<ChatMessage> responseInputToMessages(com.fasterxml.jackson.databind.JsonNode input, String instructions) {
        String userTurn = extractResponseUserTurn(input);
        if (userTurn == null) {
            throw new IllegalArgumentException("responses input must include user text");
        }
        if (blankToNull(instructions) != null) {
            return List.of(
                new ChatMessage("system", instructions.trim()),
                new ChatMessage("user", userTurn)
            );
        }
        return List.of(new ChatMessage("user", userTurn));
    }

    private String extractResponseUserTurn(com.fasterxml.jackson.databind.JsonNode input) {
        if (input == null || input.isNull()) {
            return null;
        }
        if (input.isTextual()) {
            return blankToNull(input.asText());
        }
        if (input.isArray()) {
            for (int i = input.size() - 1; i >= 0; i--) {
                com.fasterxml.jackson.databind.JsonNode item = input.get(i);
                String text = extractResponseUserTurnFromItem(item);
                if (text != null) {
                    return text;
                }
            }
            return null;
        }
        return extractResponseUserTurnFromItem(input);
    }

    private String extractResponseUserTurnFromItem(com.fasterxml.jackson.databind.JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }
        if (item.isTextual()) {
            return blankToNull(item.asText());
        }
        String role = blankToNull(item.path("role").asText(null));
        if (role != null && !"user".equalsIgnoreCase(role)) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode content = item.path("content");
        if (content.isTextual()) {
            return blankToNull(content.asText());
        }
        if (content.isArray()) {
            for (int i = 0; i < content.size(); i++) {
                com.fasterxml.jackson.databind.JsonNode part = content.get(i);
                if (part == null || part.isNull()) {
                    continue;
                }
                String type = blankToNull(part.path("type").asText(null));
                if ("input_text".equals(type) || "text".equals(type)) {
                    String text = blankToNull(part.path("text").asText(null));
                    if (text != null) {
                        return text;
                    }
                }
            }
        }
        String text = blankToNull(item.path("text").asText(null));
        if (text != null) {
            return text;
        }
        return null;
    }
}
