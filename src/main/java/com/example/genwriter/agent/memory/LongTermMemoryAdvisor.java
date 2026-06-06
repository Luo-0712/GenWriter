package com.example.genwriter.agent.memory;

import com.example.genwriter.model.dto.response.MemoryVO;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class LongTermMemoryAdvisor implements CallAroundAdvisor {

    private final LongTermMemoryService memoryService;
    private final LongTermMemoryPromptFormatter promptFormatter;
    private final List<MemoryType> memoryTypes;
    private final String sessionId;
    private final List<String> preExtractedQueries;

    public LongTermMemoryAdvisor(LongTermMemoryService memoryService,
                                 LongTermMemoryPromptFormatter promptFormatter,
                                 List<MemoryType> memoryTypes,
                                 String sessionId) {
        this(memoryService, promptFormatter, memoryTypes, sessionId, null);
    }

    public LongTermMemoryAdvisor(LongTermMemoryService memoryService,
                                 LongTermMemoryPromptFormatter promptFormatter,
                                 List<MemoryType> memoryTypes,
                                 String sessionId,
                                 List<String> preExtractedQueries) {
        this.memoryService = memoryService;
        this.promptFormatter = promptFormatter;
        this.memoryTypes = memoryTypes;
        this.sessionId = sessionId;
        this.preExtractedQueries = preExtractedQueries;
    }

    @NotNull
    @Override
    public String getName() {
        return "LongTermMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        List<String> queries = resolveQueries(request);
        if (queries.isEmpty()) {
            return chain.nextAroundCall(request);
        }

        try {
            Map<String, MemoryVO> merged = new LinkedHashMap<>();
            for (String q : queries) {
                if (q == null || q.isBlank()) continue;
                try {
                    for (MemoryVO m : memoryService.retrieveMemories(q, memoryTypes, sessionId)) {
                        merged.putIfAbsent(m.getId(), m);
                    }
                } catch (Exception e) {
                    log.debug("单个query检索失败，继续下一个: query={}, error={}", q, e.getMessage());
                }
            }

            if (merged.isEmpty()) {
                return chain.nextAroundCall(request);
            }

            String memoryContext = promptFormatter.format(new ArrayList<>(merged.values()));
            if (memoryContext.isBlank()) {
                return chain.nextAroundCall(request);
            }
            String baseSystemText = request.systemText() != null ? request.systemText() : "";
            String enhancedSystemText = baseSystemText.isBlank()
                    ? memoryContext
                    : baseSystemText + "\n\n" + memoryContext;
            AdvisedRequest enhancedRequest = AdvisedRequest.from(request)
                    .systemText(enhancedSystemText)
                    .build();

            return chain.nextAroundCall(enhancedRequest);
        } catch (Exception e) {
            log.warn("长期记忆检索失败，跳过注入: {}", e.getMessage());
            return chain.nextAroundCall(request);
        }
    }

    private List<String> resolveQueries(AdvisedRequest request) {
        if (preExtractedQueries != null && !preExtractedQueries.isEmpty()) {
            return preExtractedQueries;
        }
        String userText = extractUserText(request);
        if (userText != null && !userText.isBlank()) {
            return List.of(userText);
        }
        return List.of();
    }

    private String extractUserText(AdvisedRequest request) {
        String userText = request.userText();
        if (userText != null && !userText.isBlank()) {
            return userText;
        }

        List<Message> messages = request.messages();
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message msg = messages.get(i);
                if (msg instanceof UserMessage) {
                    String text = msg.getText();
                    if (text != null && !text.isBlank()) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

}
