package com.example.genwriter.agent.skill;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Skill Bean 注册配置
 */
@Configuration
public class SkillConfig {

    @Bean
    public OutlineSkill outlineSkill(com.example.genwriter.config.LLMConfig llmConfig) {
        return new OutlineSkill(llmConfig);
    }

    @Bean
    public DraftSkill draftSkill(com.example.genwriter.config.LLMConfig llmConfig) {
        return new DraftSkill(llmConfig);
    }

    @Bean
    public PolishSkill polishSkill(com.example.genwriter.config.LLMConfig llmConfig) {
        return new PolishSkill(llmConfig);
    }

    @Bean
    public ReviewSkill reviewSkill(com.example.genwriter.config.LLMConfig llmConfig) {
        return new ReviewSkill(llmConfig);
    }

    @Bean
    public IntentRecognitionSkill intentRecognitionSkill(com.example.genwriter.config.LLMConfig llmConfig) {
        return new IntentRecognitionSkill(llmConfig);
    }

    @Bean
    public DirectAnswerSkill directAnswerSkill(com.example.genwriter.config.LLMConfig llmConfig) {
        return new DirectAnswerSkill(llmConfig);
    }
}
