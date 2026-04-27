package com.example.genwriter.agent.skill;

import com.example.genwriter.config.LLMConfig;
import lombok.RequiredArgsConstructor;
import java.util.Map;

/**
 * 意图识别 Skill
 */
@RequiredArgsConstructor
public class IntentRecognitionSkill implements AgentSkill {

    private final LLMConfig llmConfig;

    @Override
    public String name() {
        return "intentRecognition";
    }

    @Override
    public String systemPrompt() {
        return llmConfig.getPrompts().getIntentRecognitionSystemPrompt();
    }

    @Override
    public String buildUserPrompt(Map<String, Object> context) {
        String userInput = (String) context.getOrDefault("userInput", "");
        return "## 用户输入\n\n" + userInput + "\n\n请分析以上输入的意图，按指定 JSON 格式输出。";
    }

    @Override
    public String outputFormatDescription() {
        return "JSON 格式：{intent, writingType, reason}";
    }
}
