package com.example.genwriter.agent.graph.runner;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.example.genwriter.agent.graph.checkpoint.GraphCheckpointProperties;
import com.example.genwriter.agent.graph.checkpoint.RedisCheckpointSaver;
import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.memory.MemoryProperties;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.memory.RedisChatMemory;
import com.example.genwriter.agent.supervisor.SupervisorModeProperties;
import com.example.genwriter.exception.BizException;
import com.example.genwriter.message.AgentTraceEvent;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.model.dto.MultimodalContent;
import com.example.genwriter.model.dto.response.DocumentDTO;
import com.example.genwriter.service.ChapterSummaryService;
import com.example.genwriter.service.DocumentService;
import com.example.genwriter.service.MemoryExtractionService;
import com.example.genwriter.service.MessageService;
import com.example.genwriter.service.SettingMemoryExtractionService;
import com.example.genwriter.service.SseService;
import com.example.genwriter.service.WritingOutputSettingsService;
import com.example.genwriter.service.WritingSkillExtractionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.Media;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * StateGraph 执行器（Supervisor 模式专用）
 * 编译 Supervisor Graph 并通过 SSE 实时推送各阶段状态给前端
 */
@Slf4j
@Component
public class StateGraphRunner {

    private final StateGraph supervisorGraph;
    private final SseService sseService;
    private final MessageService messageService;
    private final DocumentService documentService;
    private final RedisCheckpointSaver checkpointSaver;
    private final GraphCheckpointProperties checkpointProperties;
    private final RedisChatMemory chatMemory;
    private final MemoryProperties memoryProperties;
    private final MemoryExtractionService memoryExtractionService;
    private final SettingMemoryExtractionService settingMemoryExtractionService;
    private final WritingSkillExtractionService writingSkillExtractionService;
    private final LongTermMemoryProperties longTermMemoryProperties;
    private final WritingOutputSettingsService writingOutputSettingsService;
    private final ChapterSummaryService chapterSummaryService;
    private final SupervisorModeProperties supervisorProperties;
    private final ThoughtChainPublisher chainPublisher;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    private volatile CompiledGraph compiledSupervisorGraph;

    public StateGraphRunner(@Qualifier("supervisorGraph") StateGraph supervisorGraph,
                            SseService sseService,
                            MessageService messageService,
                            DocumentService documentService,
                            RedisCheckpointSaver checkpointSaver,
                            GraphCheckpointProperties checkpointProperties,
                            RedisChatMemory chatMemory,
                            MemoryProperties memoryProperties,
                            MemoryExtractionService memoryExtractionService,
                            SettingMemoryExtractionService settingMemoryExtractionService,
                            WritingSkillExtractionService writingSkillExtractionService,
                            LongTermMemoryProperties longTermMemoryProperties,
                            WritingOutputSettingsService writingOutputSettingsService,
                            ChapterSummaryService chapterSummaryService,
                            SupervisorModeProperties supervisorProperties,
                            ThoughtChainPublisher chainPublisher,
                            ObjectMapper objectMapper) {
        this.supervisorGraph = supervisorGraph;
        this.sseService = sseService;
        this.messageService = messageService;
        this.documentService = documentService;
        this.checkpointSaver = checkpointSaver;
        this.checkpointProperties = checkpointProperties;
        this.chatMemory = chatMemory;
        this.memoryProperties = memoryProperties;
        this.memoryExtractionService = memoryExtractionService;
        this.settingMemoryExtractionService = settingMemoryExtractionService;
        this.writingSkillExtractionService = writingSkillExtractionService;
        this.longTermMemoryProperties = longTermMemoryProperties;
        this.writingOutputSettingsService = writingOutputSettingsService;
        this.chapterSummaryService = chapterSummaryService;
        this.supervisorProperties = supervisorProperties;
        this.chainPublisher = chainPublisher;
        this.objectMapper = objectMapper;
    }

    private CompiledGraph getCompiledGraph() throws Exception {
        CompiledGraph target = compiledSupervisorGraph;
        if (target == null) {
            synchronized (this) {
                target = compiledSupervisorGraph;
                if (target == null) {
                    if (checkpointProperties.isEnabled()) {
                        SaverConfig saverConfig = SaverConfig.builder()
                                .register("redis", checkpointSaver)
                                .build();
                        CompileConfig compileConfig = CompileConfig.builder()
                                .saverConfig(saverConfig)
                                .build();
                        target = supervisorGraph.compile(compileConfig);
                    } else {
                        target = supervisorGraph.compile();
                    }
                    compiledSupervisorGraph = target;
                    log.info("Supervisor CompiledGraph 已初始化, checkpoint={}",
                            checkpointProperties.isEnabled());
                }
            }
        }
        return target;
    }

    /**
     * 执行 Supervisor StateGraph
     *
     * @param sessionId   会话 ID
     * @param documentId  文档 ID
     * @param userInput   用户输入（多模态内容）
     * @param kbId        知识库 ID
     * @param writingType 写作类型（用于兼容旧流程事件类型）
     * @param webSearch   是否启用联网搜索
     */
    public void run(String sessionId, String documentId, MultimodalContent userInput, String kbId, String writingType, boolean webSearch) {
        if (writingOutputSettingsService.isParallelChapterWritingEnabled()) {
            log.info("Supervisor 并行写章已开启，跳过会话锁: sessionId={}", sessionId);
            runSerial(sessionId, documentId, userInput, kbId, writingType, webSearch);
            return;
        }

        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, ignored -> new ReentrantLock(true));
        long waitStart = System.currentTimeMillis();
        lock.lock();
        long waitMs = System.currentTimeMillis() - waitStart;
        if (waitMs > 0) {
            log.info("Supervisor 会话锁已获取: sessionId={}, waitMs={}", sessionId, waitMs);
        }
        try {
            runSerial(sessionId, documentId, userInput, kbId, writingType, webSearch);
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                sessionLocks.remove(sessionId, lock);
            }
        }
    }

    private void runSerial(String sessionId, String documentId, MultimodalContent userInput, String kbId, String writingType, boolean webSearch) {
        sseService.startRun(sessionId);
        log.info("Supervisor 执行开始: sessionId={}, type={}, webSearch={}", sessionId, writingType, webSearch);
        publishStatus(sessionId, "【任务启动】开始处理用户请求...");

        try {
            DocumentDTO selectedDocument = loadSelectedDocument(sessionId, documentId);
            messageService.createMessage(sessionId, "user", userInput.getTextOnly());

            String context = buildContextFromMemory(sessionId);
            if (selectedDocument != null) {
                context = appendSelectedDocumentContext(context, selectedDocument);
            }

            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("sessionId", sessionId);
            inputs.put("documentId", selectedDocument != null ? selectedDocument.getId() : "");
            inputs.put("userInput", userInput.getTextOnly());
            inputs.put("multimodalContent", userInput);
            inputs.put("kbId", kbId != null ? kbId : "");
            inputs.put("writingType", writingType != null ? writingType : "AUTO");
            inputs.put("context", context);
            inputs.put("webSearch", String.valueOf(webSearch));
            if (selectedDocument != null) {
                String selectedContent = selectedDocument.getContent() != null ? selectedDocument.getContent() : "";
                inputs.put("draft", selectedContent);
                inputs.put("selectedDocumentContent", selectedContent);
                inputs.put("selectedDocumentTitle", selectedDocument.getTitle());
                inputs.put("selectedDocumentVersion", selectedDocument.getVersion());
            }

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId)
                    .build();

            CompiledGraph graph = getCompiledGraph();
            Optional<OverAllState> result = graph.call(inputs, config);

            if (result.isPresent()) {
                OverAllState state = result.get();
                String finalOutput = state.value("finalOutput", String.class).orElse(null);

                if (finalOutput != null && !finalOutput.isBlank()) {
                    publishStatus(sessionId, "【任务完成】");
                    String chapterSummary = chapterSummaryService.summarize(
                            userInput.getTextOnly(), finalOutput, writingType);
                    // 取走本 session 累积的 trace 事件（含 reasoningContent），落库到 message metadata，
                    // 前端刷新页面后可从 metadata.traceEvents 恢复 trace 树与模型思考过程。
                    List<AgentTraceEvent> traceEvents = chainPublisher.drainTraceEvents(sessionId);
                    String assistantMetadata = buildAssistantMetadata(chapterSummary, traceEvents);
                    messageService.createMessage(sessionId, "assistant", finalOutput, assistantMetadata);
                    saveToMemory(sessionId, userInput, finalOutput, chapterSummary);

                    if (longTermMemoryProperties.isEnabled()) {
                        try {
                            memoryExtractionService.extractAsync(sessionId, userInput.getTextOnly(), finalOutput);
                        } catch (Exception e) {
                            log.warn("触发长期记忆提取失败: sessionId={}", sessionId, e);
                        }
                        try {
                            settingMemoryExtractionService.extractAsync(sessionId, userInput.getTextOnly(), finalOutput);
                        } catch (Exception e) {
                            log.warn("Trigger setting memory extraction failed: sessionId={}", sessionId, e);
                        }
                        if (longTermMemoryProperties.getWritingSkillExtraction().isEnabled()) {
                            try {
                                writingSkillExtractionService.extractAsync(sessionId, userInput.getTextOnly(), finalOutput, writingType);
                            } catch (Exception e) {
                                log.warn("触发写作技巧提取失败: sessionId={}", sessionId, e);
                            }
                        }
                    }
                } else {
                    publishStatus(sessionId, "【任务结束】未产生输出");
                }
            } else {
                publishStatus(sessionId, "【任务结束】未产生输出");
            }

        } catch (Exception e) {
            log.error("Supervisor 执行失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            publishStatus(sessionId, "【任务失败】" + e.getMessage());
            String errorMessage = "处理失败：" + e.getMessage();
            persistErrorMessage(sessionId, errorMessage);
            sendErrorMessage(sessionId, errorMessage);
        } finally {
            // 无论成功/失败，释放本 session 的 trace 缓冲，防止内存泄漏
            // （成功路径已 drain 取走，此处对失败路径兜底；drain 已清空时 clear 为 no-op）
            chainPublisher.clearTraceEvents(sessionId);
        }
    }

    private DocumentDTO loadSelectedDocument(String sessionId, String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return null;
        }
        DocumentDTO document = documentService.getDocumentById(documentId);
        if (!sessionId.equals(document.getSessionId())) {
            throw new BizException(BizException.ErrorCode.FORBIDDEN);
        }
        return document;
    }

    private String appendSelectedDocumentContext(String context, DocumentDTO document) {
        StringBuilder sb = new StringBuilder(context != null ? context : "");
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append("\n");
        }
        sb.append("\n## 当前选中文稿版本\n")
                .append("标题: ").append(document.getTitle() != null ? document.getTitle() : "未命名文稿").append("\n")
                .append("版本: V").append(document.getVersion() != null ? document.getVersion() : 1).append("\n")
                .append("内容:\n")
                .append(document.getContent() != null ? document.getContent() : "")
                .append("\n");
        return sb.toString();
    }

    private String buildContextFromMemory(String sessionId) {
        try {
            List<Message> history = chatMemory.getAllMessages(sessionId);
            if (history.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("以下为本次会话的历史对话记录：\n");
            boolean chapterContextEnabled = supervisorProperties.getChapterContext().isEnabled();
            for (Message msg : history) {
                String role = msg.getMessageType().getValue();
                String text = msg.getText();
                if (msg instanceof UserMessage userMessage
                        && userMessage.getMedia() != null && !userMessage.getMedia().isEmpty()) {
                    text += "[包含 " + userMessage.getMedia().size() + " 张图片]";
                }
                if (chapterContextEnabled && msg instanceof AssistantMessage) {
                    continue;
                }
                sb.append("[").append(role).append("]: ")
                        .append(text).append("\n");
            }
            appendRecentChapterSummaries(sb, history);
            return sb.toString();
        } catch (Exception e) {
            log.warn("加载对话历史失败: sessionId={}", sessionId, e);
            return "";
        }
    }

    private void saveToMemory(String sessionId, MultimodalContent userInput, String assistantOutput, String chapterSummary) {
        try {
            UserMessage userMessage;
            if (userInput.hasImages()) {
                List<Media> mediaList = userInput.getImageAttachments().stream()
                        .map(a -> {
                            try {
                                return new Media(
                                        MimeType.valueOf(a.getMimeType()),
                                        new org.springframework.core.io.UrlResource(a.getFileUrl()));
                            } catch (Exception ex) {
                                throw new RuntimeException("Invalid URL: " + a.getFileUrl(), ex);
                            }
                        })
                        .toList();
                userMessage = new UserMessage(userInput.getTextOnly(), mediaList);
            } else {
                userMessage = new UserMessage(userInput.getTextOnly());
            }
            List<Message> messages = List.of(
                    userMessage,
                    new AssistantMessage(assistantOutput, assistantMetadataMap(chapterSummary))
            );
            chatMemory.add(sessionId, messages);
            log.debug("对话记忆已保存: sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("保存对话记忆失败: sessionId={}", sessionId, e);
        }
    }

    private void appendRecentChapterSummaries(StringBuilder sb, List<Message> history) {
        SupervisorModeProperties.ChapterContext properties = supervisorProperties.getChapterContext();
        if (!properties.isEnabled()) {
            return;
        }
        List<String> summaries = new java.util.ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && summaries.size() < properties.getMaxSummaries(); i--) {
            Message msg = history.get(i);
            if (msg instanceof AssistantMessage) {
                String summary = chapterSummary(msg);
                if (summary != null && !summary.isBlank()) {
                    summaries.add(0, truncate(summary, properties.getMaxSummaryChars()));
                }
            }
        }
        if (summaries.isEmpty()) {
            return;
        }
        sb.append("\n以下为最近章节的连续性摘要，只可作为已发生叙事状态参考，不要当作新设定扩写：\n");
        for (int i = 0; i < summaries.size(); i++) {
            sb.append("第").append(i + 1).append("段摘要：")
                    .append(summaries.get(i))
                    .append("\n");
        }
    }

    private String chapterSummary(Message message) {
        Map<String, Object> metadata = message.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        Object chapterContinuity = metadata.get("chapterContinuity");
        if (chapterContinuity instanceof Map<?, ?> map) {
            Object summary = map.get("summary");
            return summary != null ? String.valueOf(summary) : "";
        }
        return "";
    }

    private String buildAssistantMetadata(String chapterSummary,
                                          List<AgentTraceEvent> traceEvents) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> chapterMeta = assistantMetadataMap(chapterSummary);
        if (chapterMeta != null) {
            metadata.putAll(chapterMeta);
        }
        // trace 事件落库：前端从 metadata.traceEvents 恢复 trace 树与 reasoningContent
        if (traceEvents != null && !traceEvents.isEmpty()) {
            metadata.put("traceEvents", traceEvents);
        }
        if (metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("assistant metadata 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private void persistErrorMessage(String sessionId, String errorMessage) {
        try {
            Map<String, Object> metadata = Map.of(
                    "error", true,
                    "source", "state_graph_runner"
            );
            messageService.createMessage(sessionId, "assistant", errorMessage,
                    objectMapper.writeValueAsString(metadata));
        } catch (Exception e) {
            log.warn("持久化错误消息失败: sessionId={}", sessionId, e);
        }
    }

    private Map<String, Object> assistantMetadataMap(String chapterSummary) {
        if (chapterSummary == null || chapterSummary.isBlank()) {
            return Map.of();
        }
        return Map.of("chapterContinuity", Map.of(
                "summary", chapterSummary,
                "kind", "chapter_summary"
        ));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        int limit = Math.max(1, maxLen);
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private void publishStatus(String sessionId, String statusText) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_THINKING)
                    .payload(SseMessage.Payload.builder()
                            .statusText(statusText)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE 状态推送失败: {}", e.getMessage());
        }
    }

    private void sendErrorMessage(String sessionId, String errorMessage) {
        try {
            SseMessage message = SseMessage.builder()
                    .type(SseMessage.Type.ERROR)
                    .payload(SseMessage.Payload.builder()
                            .data(errorMessage)
                            .build())
                    .build();
            sseService.publish(sessionId, message);
            sseService.complete(sessionId);
        } catch (Exception ex) {
            log.error("发送错误消息失败: sessionId={}", sessionId, ex);
        }
    }
}
