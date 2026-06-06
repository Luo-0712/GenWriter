package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.streaming.ContentStreamPublisher;
import com.example.genwriter.model.dto.request.CreateDocumentRequest;
import com.example.genwriter.model.dto.response.DocumentDTO;
import com.example.genwriter.service.DocumentService;
import com.example.genwriter.service.SseService;
import com.example.genwriter.service.WritingOutputSettingsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 发布节点
 * 将 Graph 执行结果发布到 SSE 频道
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsePublishNode implements NodeAction {

    private final SseService sseService;
    private final ContentStreamPublisher contentStreamPublisher;
    private final ObjectMapper objectMapper;
    private final DocumentService documentService;
    private final WritingOutputSettingsService writingOutputSettingsService;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", String.class).orElse("");
        String documentId = state.value("documentId", String.class).orElse(null);
        String finalOutput = state.value("finalOutput", String.class).orElse("");

        if (finalOutput != null && !finalOutput.isBlank()) {
            DocumentDTO generatedDocument = null;
            try {
                generatedDocument = createGeneratedDocumentVersion(state, sessionId, documentId, finalOutput);
            } catch (Exception e) {
                log.warn("保存生成文稿版本失败: sessionId={}, documentId={}, error={}",
                        sessionId, documentId, e.getMessage(), e);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content", finalOutput);
            data.put("finalContent", true);
            if (generatedDocument != null) {
                data.put("document", toDocumentSummary(generatedDocument));
            }

            String researchSourcesJson = state.value("researchSources", String.class).orElse(null);
            if (researchSourcesJson != null && !researchSourcesJson.isBlank()) {
                try {
                    List<Map<String, String>> sources = objectMapper.readValue(
                            researchSourcesJson, new TypeReference<>() {});
                    if (!sources.isEmpty()) {
                        data.put("sources", sources);
                    }
                } catch (Exception e) {
                    log.warn("解析 researchSources 失败: {}", e.getMessage());
                }
            }

            contentStreamPublisher.publishFinal(
                    sessionId,
                    finalOutput,
                    data.get("document"),
                    data.get("sources"),
                    generatedDocument != null ? generatedDocument.getId() : documentId
            );
            log.debug("SSE 内容已发布: sessionId={}, length={}", sessionId, finalOutput.length());
        }

        // 发送完成信号
        sseService.complete(sessionId);
        log.debug("SSE 完成信号已发送: sessionId={}", sessionId);

        return Map.of("currentNode", "SsePublishNode");
    }

    private DocumentDTO createGeneratedDocumentVersion(OverAllState state, String sessionId,
                                                       String sourceDocumentId, String finalOutput) throws Exception {
        if (sessionId == null || sessionId.isBlank() || isKnowledgeQaResult(state)) {
            return null;
        }

        String title = deriveTitle(
                state.value("selectedDocumentTitle", String.class).orElse(""),
                finalOutput
        );

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("generatedBy", "agent");
        metadata.put("instruction", state.value("userInput", String.class).orElse(""));
        metadata.put("writingType", state.value("writingType", String.class).orElse("AUTO"));
        String outputFormat = state.value("outputFormat", String.class)
                .orElse(writingOutputSettingsService.currentFormat());
        metadata.put("writingGenre", state.value("writingGenre", String.class).orElse("UNKNOWN"));
        metadata.put("outputFormat", outputFormat);

        if (sourceDocumentId != null && !sourceDocumentId.isBlank()) {
            metadata.put("derivedFromDocumentId", sourceDocumentId);
        }
        state.value("selectedDocumentVersion", Integer.class)
                .ifPresent(version -> metadata.put("derivedFromVersion", version));

        CreateDocumentRequest request = CreateDocumentRequest.builder()
                .sessionId(sessionId)
                .title(title)
                .content(finalOutput)
                .type("draft")
                .format(outputFormat)
                .status("editing")
                .metadata(objectMapper.writeValueAsString(metadata))
                .build();

        return documentService.createNewVersion(sessionId, request);
    }

    private boolean isKnowledgeQaResult(OverAllState state) {
        String writingType = state.value("writingType", String.class).orElse("");
        String intent = state.value("intent", String.class).orElse("");
        return "KNOWLEDGE_QA".equalsIgnoreCase(writingType)
                || "KNOWLEDGE_QA".equalsIgnoreCase(intent);
    }

    private String deriveTitle(String selectedTitle, String content) {
        if (selectedTitle != null && !selectedTitle.isBlank()) {
            return selectedTitle.trim();
        }
        String fallback = "创作草稿";
        if (content == null || content.isBlank()) {
            return fallback;
        }
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.replaceFirst("^#{1,6}\\s*", "").trim();
            if (!line.isBlank()) {
                return line.length() > 80 ? line.substring(0, 80) : line;
            }
        }
        return fallback;
    }

    private Map<String, Object> toDocumentSummary(DocumentDTO document) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", document.getId());
        summary.put("sessionId", document.getSessionId());
        summary.put("title", document.getTitle());
        summary.put("version", document.getVersion());
        summary.put("status", document.getStatus());
        summary.put("updatedAt", document.getUpdatedAt() != null ? document.getUpdatedAt().toString() : null);
        return summary;
    }
}
