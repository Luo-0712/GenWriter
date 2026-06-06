package com.example.genwriter.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM调用统摄配置
 * 集中管理所有大语言模型相关的配置参数
 * 使用Spring AI Alibaba，提供商配置由spring.ai.alibaba处理
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "genwriter.llm")
public class LLMConfig {

    /**
     * 默认使用的模型提供商 (dashscope/openai)
     */
    private String defaultProvider = "dashscope";

    /**
     * 默认模型名称
     */
    private String defaultModel = "qwen-max";

    /**
     * 请求超时时间
     */
    private Duration timeout = Duration.ofMinutes(15);

    /**
     * 最大重试次数
     */
    private Integer maxRetries = 3;

    /**
     * 默认温度参数 (0.0 - 2.0)
     */
    private Double defaultTemperature = 0.7;

    /**
     * 默认最大生成token数
     */
    private Integer defaultMaxTokens = 65535;

    /**
     * 提示词模板配置
     */
    private PromptTemplates prompts = new PromptTemplates();

    /**
     * 已配置的模型供应商列表
     */
    private List<ProviderConfig> providers = new ArrayList<>();

    /**
     * 提示词管理器（可选注入，用于外部 YAML 提示词加载）
     */
    private PromptManager promptManager;

    @Autowired(required = false)
    public void setPromptManager(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    /**
     * 按优先级链解析提示词：application.yml > 外部 YAML > Java 内联默认值
     *
     * @param currentValue 当前值（来自 @ConfigurationProperties 绑定，可能为空字符串）
     * @param yamlKey      外部 YAML 文件中的 key
     * @return 解析后的提示词
     */
    public String resolvePrompt(String currentValue, String yamlKey) {
        if (currentValue != null && !currentValue.isBlank()) {
            return currentValue;
        }
        if (promptManager != null) {
            String fromYaml = promptManager.getPrompt(yamlKey);
            if (fromYaml != null && !fromYaml.isBlank()) {
                return fromYaml;
            }
        }
        return currentValue;
    }

    /**
     * 单个供应商配置
     */
    @Data
    public static class ProviderConfig {
        /**
         * 供应商类型: dashscope/openai/deepseek/anthropic
         */
        private String type;
        /**
         * 显示名称
         */
        private String displayName;
        /**
         * API Key
         */
        private String apiKey;
        /**
         * 自定义 Base URL（OpenAI 兼容 API 使用）
         */
        private String baseUrl;
        /**
         * API 兼容性: native/openai_compatible
         */
        private String apiCompatibility = "native";
        /**
         * 可用模型列表
         */
        private List<String> models = new ArrayList<>();
        /**
         * 当前选中的模型
         */
        private String activeModel;
        /**
         * 是否为推理模型（如 DeepSeek-R1），支持 reasoning_content 流式输出
         */
        private boolean reasoning = false;
    }

    /**
     * 提示词模板配置
     */
    @Data
    public static class PromptTemplates {

        // ============================================================
        // Graph Node Skill Prompts (可配置覆盖)
        // ============================================================

        private String outlineSystemPrompt = "";

        private String draftSystemPrompt = "";

        private String polishSystemPrompt = "";

        private String reviewSystemPrompt = "";

        private String intentRecognitionSystemPrompt = "";

        private String directAnswerSystemPrompt = "";

        private String researcherSystemPrompt = "";

        private String researcherPlanningPrompt = "";

        private String researcherSynthesisPrompt = "";

        private String researcherVerificationPrompt = "";

        private String titleSummaryPrompt = """
            请根据用户的对话内容，生成一个简短的对话标题。

            要求：
            1. 标题长度不超过20个字符
            2. 准确概括对话的核心主题
            3. 不要使用引号、书名号等标点
            4. 只输出标题文本，不要输出任何其他内容

            用户消息：{userMessage}
            """;

        /**
         * Supervisor 监督者系统提示词（可覆盖）
         * 留空时使用 SupervisorSystemPromptProvider 动态构建的默认提示词
         */
        private String supervisorSystemPrompt = "";

        private String memoryExtractionPrompt = "";

        /**
         * 写作技巧对话提取提示词模板
         * 用于从用户与助手的对话中提取可复用的写作技巧
         * 可用占位符：{userInput}, {assistantOutput}, {writingType}
         */
        private String writingSkillExtractionPrompt = "";

        /**
         * 文章风格学习提示词模板
         * 用于从示例文章中提取可复用的写作技法和风格特征
         * 可用占位符：{articleContent}, {description}
         */
        private String articleSkillLearningPrompt = "";

        /**
         * 文章关键细节提取提示词模板
         * 用于从长文章中提取需要核查的关键细节，作为长期记忆检索的query
         * 可用占位符：{articleContent}
         */
        private String articleDetailExtractionPrompt = """
            你是一个文章细节提取助手。请从给定的文章中提取3-5个关键细节或核查要点，这些细节可能需要参考历史记忆来确认一致性。

            关注以下类型的细节：
            - 人物设定（姓名、身份、性格、关系等）
            - 时间线与历史事件
            - 地点与场景描述
            - 专有名词、术语
            - 风格或语气特征
            - 前后文可能需要呼应的情节

            输出格式：纯JSON数组，不要markdown代码块
            ["核查要点1", "核查要点2", "核查要点3"]
            """;
    }
}
