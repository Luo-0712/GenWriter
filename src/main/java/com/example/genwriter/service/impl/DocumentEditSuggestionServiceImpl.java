package com.example.genwriter.service.impl;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryPromptFormatter;
import com.example.genwriter.exception.BizException;
import com.example.genwriter.model.dto.request.DocumentEditSuggestionRequest;
import com.example.genwriter.model.dto.response.DocumentDTO;
import com.example.genwriter.model.dto.response.DocumentEditSuggestionResponse;
import com.example.genwriter.model.dto.response.MemoryVO;
import com.example.genwriter.model.enums.DocumentEditSuggestionMode;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.DocumentEditSuggestionService;
import com.example.genwriter.service.DocumentService;
import com.example.genwriter.service.LongTermMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentEditSuggestionServiceImpl implements DocumentEditSuggestionService {

    private static final double TEMPERATURE = 0.5;

    private final DocumentService documentService;
    private final ChatClientFactory chatClientFactory;
    private final LongTermMemoryService memoryService;
    private final LongTermMemoryPromptFormatter memoryPromptFormatter;

    @Override
    public DocumentEditSuggestionResponse suggestEdit(String documentId, DocumentEditSuggestionRequest request) {
        DocumentDTO document = documentService.getDocumentById(documentId);
        String memoryContext = retrieveMemoryContext(document.getSessionId(), request);
        String output = callModel(document, request, memoryContext);
        String replacement = cleanModelOutput(output);
        validateReplacement(replacement, request);

        return DocumentEditSuggestionResponse.builder()
                .mode(request.getMode())
                .replacementMarkdown(replacement)
                .selectionFingerprint(request.getSelectionFingerprint())
                .createdAt(LocalDateTime.now())
                .build();
    }

    private String retrieveMemoryContext(String sessionId, DocumentEditSuggestionRequest request) {
        try {
            String query = String.join("\n",
                    safe(request.getInstruction()),
                    safe(request.getSelectedText()),
                    safe(request.getBeforeText()),
                    safe(request.getAfterText()));
            List<MemoryVO> memories = memoryService.retrieveMemories(
                    query,
                    List.of(MemoryType.WRITING_PREFERENCE, MemoryType.WRITING_TECHNIQUE),
                    sessionId);
            return memoryPromptFormatter.format(memories);
        } catch (Exception e) {
            log.debug("局部编辑长期记忆检索失败，跳过注入: {}", e.getMessage());
            return "";
        }
    }

    private String callModel(DocumentDTO document, DocumentEditSuggestionRequest request, String memoryContext) {
        try {
            ChatClient client = chatClientFactory.create(TEMPERATURE);
            return client.prompt()
                    .system(systemPrompt(request.getMode(), memoryContext))
                    .user(userPrompt(document, request))
                    .call()
                    .content();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("局部编辑建议生成失败: documentId={}, mode={}, error={}", document.getId(), request.getMode(), e.getMessage());
            throw new BizException(BizException.ErrorCode.AI_SERVICE_ERROR, e);
        }
    }

    private String systemPrompt(DocumentEditSuggestionMode mode, String memoryContext) {
        String modeRule = switch (mode) {
            case POLISH_SELECTION -> "润色选区：保持原意，只优化表达、节奏、清晰度和文采。";
            case REWRITE_SELECTION -> "重写选区：按用户补充要求重写选区，可调整表达结构，但不要改动选区外内容。";
            case CONTINUE_AFTER_SELECTION -> "选区后续写：只返回应插入到选区之后的新内容，不要重复选区原文。";
        };
        return """
                你是 Markdown 文稿的局部选区编辑器。
                %s
                必须遵守：
                1. 只返回可直接插入文档的 Markdown 片段。
                2. 不要返回说明、标题、diff、完整文档或 Markdown 代码围栏。
                3. 不要输出选区前后的上下文，不要改动未选中内容。
                4. 保持原语言、叙事视角、术语、人物称谓和 Markdown 结构。
                5. 用户本轮补充要求优先于长期记忆。
                %s
                """.formatted(modeRule, memoryContext == null || memoryContext.isBlank() ? "" : "\n" + memoryContext);
    }

    private String userPrompt(DocumentDTO document, DocumentEditSuggestionRequest request) {
        return """
                文档标题：%s
                当前文档版本：%s
                前端版本：%s
                编辑模式：%s
                用户补充要求：%s

                [选区前上下文]
                %s
                [/选区前上下文]

                [当前选区 Markdown]
                %s
                [/当前选区 Markdown]

                [当前选区纯文本]
                %s
                [/当前选区纯文本]

                [选区后上下文]
                %s
                [/选区后上下文]

                现在只返回结果 Markdown 片段。
                """.formatted(
                firstNonBlank(request.getTitle(), document.getTitle(), "未命名文稿"),
                document.getVersion(),
                request.getClientDocumentVersion(),
                request.getMode(),
                firstNonBlank(request.getInstruction(), "无"),
                safe(request.getBeforeText()),
                safe(request.getSelectedMarkdown()),
                safe(request.getSelectedText()),
                safe(request.getAfterText())
        );
    }

    private String cleanModelOutput(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```$", "");
        }
        return cleaned.trim();
    }

    private void validateReplacement(String replacement, DocumentEditSuggestionRequest request) {
        if (replacement.isBlank()) {
            throw new BizException(BizException.ErrorCode.AI_SERVICE_ERROR);
        }
        int selectedLength = safe(request.getSelectedMarkdown()).length();
        int maxLength = Math.max(selectedLength * 4, 12000);
        if (replacement.length() > maxLength) {
            throw new BizException(BizException.ErrorCode.AI_SERVICE_ERROR);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
