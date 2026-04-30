package com.example.genwriter.agent.memory;

import com.example.genwriter.model.dto.response.MemoryVO;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class LongTermMemoryAdvisor implements CallAroundAdvisor {

    private final LongTermMemoryService memoryService;
    private final List<MemoryType> memoryTypes;
    private final String sessionId;
    private final String documentId;

    public LongTermMemoryAdvisor(LongTermMemoryService memoryService,
                                 List<MemoryType> memoryTypes,
                                 String sessionId,
                                 String documentId) {
        this.memoryService = memoryService;
        this.memoryTypes = memoryTypes;
        this.sessionId = sessionId;
        this.documentId = documentId;
    }

    @Override
    public String getName() {
        return "LongTermMemoryAdvisor";
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        String query = extractUserText(request);
        if (query == null || query.isBlank()) {
            return chain.nextCall(request);
        }

        try {
            List<MemoryVO> memories = memoryService.retrieveMemories(
                    query, memoryTypes, sessionId, documentId);

            if (memories.isEmpty()) {
                return chain.nextCall(request);
            }

            String memoryContext = formatMemories(memories);
            AdvisedRequest enhancedRequest = AdvisedRequest.from(request)
                    .systemText(request.systemText() + "\n\n" + memoryContext)
                    .build();

            return chain.nextCall(enhancedRequest);
        } catch (Exception e) {
            log.warn("长期记忆检索失败，跳过注入: {}", e.getMessage());
            return chain.nextCall(request);
        }
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

    private String formatMemories(List<MemoryVO> memories) {
        StringBuilder sb = new StringBuilder();
        sb.append("[长期记忆] 以下是从历史交互中保存的相关信息，供参考：\n");
        for (MemoryVO m : memories) {
            sb.append("- [").append(m.getMemoryType()).append("] ").append(m.getContent()).append("\n");
        }
        return sb.toString();
    }
}
