package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.message.AgentTraceEvent;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.model.dto.response.LearningResultVO;
import com.example.genwriter.service.WritingSkillLearningService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WritingSkillExtractorWorker implements WorkerAgent {

    private static final double TEMPERATURE = 0.2;

    private final ChatClientFactory chatClientFactory;
    private final WorkerRegistry registry;
    private final WritingSkillLearningService learningService;
    private final ThoughtChainPublisher chainPublisher;

    private ChatClient chatClient;

    @PostConstruct
    void init() {
        this.chatClient = chatClientFactory.create(TEMPERATURE);
        registry.register(this);
    }

    @Override
    public String name() {
        return "writing_skill_extractor";
    }

    @Override
    public String description() {
        return "从用户提供的示例文章或写作指导中提取可复用的写作技巧，并保存到长期记忆";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> state) throws Exception {
        String sessionId = (String) state.getOrDefault("sessionId", "");
        String userInput = (String) state.getOrDefault("userInput", "");

        String nodeId = chainPublisher.publishStart(sessionId, "写作技巧提取",
                ChainNode.Type.EXECUTION, null,
                Map.of("userInputLength", userInput.length()));

        String serviceSpanId = chainPublisher.publishTraceStart(sessionId, "分析并保存写作技巧",
                AgentTraceEvent.Kind.WORKER, nodeId,
                Map.of("userInputLength", userInput.length()), null);
        try {
            LearningResultVO result = learningService.analyzeAndStore(
                    userInput,
                    null,
                    "GLOBAL",
                    null,
                    sessionId
            );

            String finalOutput;
            if (result.isSuccess()) {
                finalOutput = "已学习 " + result.getStoredCount() + " 条写作技巧：\n" +
                        result.getSkills().stream()
                                .map(s -> "- " + s.getSkillName() + "（" + s.getCategory() + "）")
                                .reduce((a, b) -> a + "\n" + b)
                                .orElse("");
                chainPublisher.publishComplete(sessionId, nodeId,
                        Map.of("storedCount", result.getStoredCount(), "extractedCount", result.getExtractedCount()));
            } else {
                finalOutput = "技巧提取完成，但未保存有效内容：" + result.getMessage();
                chainPublisher.publishComplete(sessionId, nodeId,
                        Map.of("success", false, "message", result.getMessage()));
            }
            chainPublisher.publishTraceComplete(sessionId, serviceSpanId,
                    Map.of("success", result.isSuccess(),
                            "storedCount", result.getStoredCount(),
                            "extractedCount", result.getExtractedCount()));

            return Map.of(
                    "learningReport", result.getMessage(),
                    "finalOutput", finalOutput,
                    "storedCount", result.getStoredCount()
            );
        } catch (Exception e) {
            chainPublisher.publishError(sessionId, nodeId, e.getMessage());
            chainPublisher.publishTraceError(sessionId, serviceSpanId, e.getMessage());
            log.error("[WritingSkillExtractorWorker] 执行失败: sessionId={}", sessionId, e);
            return Map.of(
                    "learningReport", "提取失败: " + e.getMessage(),
                    "finalOutput", "写作技巧提取过程中出现错误，请稍后重试。",
                    "storedCount", 0
            );
        }
    }
}
