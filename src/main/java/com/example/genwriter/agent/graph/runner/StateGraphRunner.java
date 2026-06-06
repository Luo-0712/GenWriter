package com.example.genwriter.agent.graph.runner;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.example.genwriter.agent.graph.checkpoint.GraphCheckpointProperties;
import com.example.genwriter.agent.graph.checkpoint.RedisCheckpointSaver;
import com.example.genwriter.agent.memory.MemoryProperties;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.memory.RedisChatMemory;
import com.example.genwriter.exception.BizException;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.model.dto.MultimodalContent;
import com.example.genwriter.model.dto.response.DocumentDTO;
import com.example.genwriter.service.DocumentService;
import com.example.genwriter.service.MemoryExtractionService;
import com.example.genwriter.service.MessageService;
import com.example.genwriter.service.SettingMemoryExtractionService;
import com.example.genwriter.service.SseService;
import com.example.genwriter.service.WritingSkillExtractionService;
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
                            LongTermMemoryProperties longTermMemoryProperties) {
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
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, ignored -> new ReentrantLock(true));
        lock.lock();
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

                publishStatus(sessionId, "【任务完成】");

                if (finalOutput != null && !finalOutput.isBlank()) {
                    messageService.createMessage(sessionId, "assistant", finalOutput);
                    saveToMemory(sessionId, userInput, finalOutput);

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
                }
            } else {
                publishStatus(sessionId, "【任务完成】未产生输出");
            }

        } catch (Exception e) {
            log.error("Supervisor 执行失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            publishStatus(sessionId, "【任务失败】" + e.getMessage());
            sendErrorMessage(sessionId, "处理失败：" + e.getMessage());
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
            for (Message msg : history) {
                String role = msg.getMessageType().getValue();
                String text = msg.getText();
                if (msg instanceof UserMessage userMessage
                        && userMessage.getMedia() != null && !userMessage.getMedia().isEmpty()) {
                    text += "[包含 " + userMessage.getMedia().size() + " 张图片]";
                }
                sb.append("[").append(role).append("]: ")
                        .append(text).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("加载对话历史失败: sessionId={}", sessionId, e);
            return "";
        }
    }

    private void saveToMemory(String sessionId, MultimodalContent userInput, String assistantOutput) {
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
                    new AssistantMessage(assistantOutput)
            );
            chatMemory.add(sessionId, messages);
            log.debug("对话记忆已保存: sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("保存对话记忆失败: sessionId={}", sessionId, e);
        }
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
