package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.skill.OutlineSkill;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutlineGenerationWorker implements WorkerAgent {

    private static final double TEMPERATURE = 0.3;

    private final ChatClientFactory chatClientFactory;
    private final OutlineSkill skill;
    private final WorkerRegistry registry;

    private ChatClient chatClient;

    @PostConstruct
    void init() {
        this.chatClient = chatClientFactory.create(TEMPERATURE);
        registry.register(this);
    }

    @Override
    public String name() {
        return "outline";
    }

    @Override
    public String description() {
        return "根据用户输入和上下文设计结构化文章大纲";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> state) throws Exception {
        String userInput = (String) state.getOrDefault("userInput", "");
        String context = (String) state.getOrDefault("context", "");

        String userPrompt = skill.buildUserPrompt(Map.of("userInput", userInput, "context", context));
        String response = chatClient.prompt()
                .system(skill.systemPrompt())
                .user(userPrompt)
                .call()
                .content();

        log.info("大纲生成完成: length={}", response.length());
        return Map.of("outline", response);
    }
}
