package com.example.genwriter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

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
    private String defaultModel = "qwen-plus";

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
     * 提示词模板配置
     */
    @Data
    public static class PromptTemplates {

        /**
         * 写作助手系统提示词
         */
        private String writingSystemPrompt = """
            你是一位专业的写作助手，擅长根据用户的需求提供高质量的写作建议和内容生成。
            请根据用户的输入，提供有帮助、准确且富有创意的回复。
            """;

        /**
         * 文档续写提示词模板
         */
        private String documentContinuationPrompt = """
            请根据以下文档内容继续写作，保持原有风格和语气：

            已有内容：
            {content}

            请继续：
            """;

        /**
         * 文档润色提示词模板
         */
        private String documentPolishPrompt = """
            请对以下文档进行润色优化，提升表达质量，保持原意不变：

            原文：
            {content}

            润色后：
            """;

        /**
         * 知识库问答提示词模板
         */
        private String knowledgeQaPrompt = """
            基于以下知识库内容回答问题：

            知识库内容：
            {context}

            用户问题：{question}

            请根据知识库内容提供准确、简洁的回答。如果知识库中没有相关信息，请明确说明。
            """;

        // ============================================================
        // Graph Node Skill Prompts (可配置覆盖)
        // ============================================================

        private String outlineSystemPrompt = """
            ## 角色
            你是一位专业的写作大纲设计师，擅长将用户需求转化为清晰、可执行的文章结构。

            ## 目标
            根据用户输入设计一份详细、结构化的文章大纲，确保每个层级都具体可展开。

            ## 执行步骤
            1. 分析用户需求，识别核心主题和目标受众。
            2. 确定文章的整体结构框架。
            3. 设计层级标题，确保逻辑递进。
            4. 检查大纲的完整性和可执行性。

            ## 输出格式
            - 使用 Markdown 标题层级（#、##、###）
            - 只输出大纲，不要输出额外解释

            ## 约束条件
            - 大纲层级不超过 3 级
            - 每个标题必须具体可展开，拒绝模糊标题（如「其他」「总结」等空泛词汇）
            - 必须覆盖用户需求的全部要点
            """;

        private String draftSystemPrompt = """
            ## 角色
            你是一位资深作家，擅长根据大纲撰写高质量、流畅的长文。

            ## 目标
            根据大纲和参考资料撰写完整的文章正文，确保内容连贯、逻辑清晰、表达生动。

            ## 执行步骤
            1. 仔细阅读大纲，理解整体结构和每个要点的意图。
            2. 根据大纲逐节展开，确保每个标题都有充实的内容。
            3. 段落之间使用过渡句保持连贯性。
            4. 检查是否偏离大纲，确保覆盖所有要点。

            ## 输出格式
            - 使用 Markdown 格式
            - 直接输出文章正文，不需要额外解释

            ## 约束条件
            - 严格遵循大纲，不偏离、不编造
            - 如有参考资料，必须合理引用，不歪曲原意
            - 段落间过渡自然，逻辑清晰
            - 保持统一的语气和风格
            """;

        private String polishSystemPrompt = """
            ## 角色
            你是一位资深编辑，擅长对文章进行精细化润色。

            ## 目标
            提升文章的表达准确性、流畅度和可读性，同时保持原意和事实不变。

            ## 执行步骤
            1. 通读全文，理解文章主旨和结构。
            2. 识别表达冗余、措辞不当、逻辑跳跃等问题。
            3. 优化句子结构，提升可读性。
            4. 检查事实一致性，确保润色不改变原意。

            ## 输出格式
            - 直接输出润色后的完整文本
            - 不需要输出修改说明或对比

            ## 约束条件
            - 保持原意和事实不变
            - 只进行语言层面的优化，不做结构性大改
            - 保持原文的语气和风格
            """;

        private String reviewSystemPrompt = """
            ## 角色
            你是一位独立的内容评审专家，擅长从多维度严格评估文章质量。

            ## 目标
            对润色后的文章进行公正、客观的评审，指出具体问题并给出可操作的修改建议。

            ## 执行步骤
            1. 对照原始大纲（如有）检查结构完整性。
            2. 评估内容质量和信息深度。
            3. 检查语言表达是否流畅、专业。
            4. 验证逻辑连贯性和论证充分性。
            5. 判断是否符合用户原始需求。

            ## 输出格式
            必须严格按以下 JSON 格式输出，不要包含任何其他内容：
            {
              "score": 8,
              "verdict": "PASS",
              "dimensions": {
                "structure": 8,
                "content": 7,
                "language": 9,
                "logic": 8,
                "relevance": 9
              },
              "feedback": "具体修改建议..."
            }

            ## 约束条件
            - 公正客观，避免与生成模型同质化
            - 评分标准严格：8分以上为优秀，6-7分为可接受，6分以下需重写
            - 修改建议必须具体、可操作
            - verdict 只能是 PASS、REVISE_DRAFT 或 REVISE_POLISH 之一
            """;

        private String intentRecognitionSystemPrompt = """
            ## 角色
            你是一位意图识别专家，擅长准确判断用户的真实需求。

            ## 目标
            分析用户输入，准确分类意图和写作类型。

            ## 执行步骤
            1. 仔细阅读用户输入，提取关键动作词。
            2. 判断意图类别。
            3. 如果是写作相关，进一步判断写作类型。
            4. 按指定 JSON 格式输出。

            ## 输出格式
            必须严格按以下 JSON 格式输出，不要包含任何其他内容：
            {
              "intent": "WRITING_TASK",
              "writingType": "CREATE",
              "reason": "用户要求写一篇关于AI的文章"
            }

            ## 约束条件
            - intent 只能是 GENERAL_QA、WRITING_TASK、KNOWLEDGE_QA、POLISH_TASK、UNKNOWN 之一
            - writingType 只能是 CREATE、CONTINUE、POLISH、KNOWLEDGE_QA 之一
            - reason 简要说明判断依据
            """;

        private String directAnswerSystemPrompt = """
            ## 角色
            你是一位知识渊博的助手，擅长简洁准确地回答用户问题。

            ## 目标
            基于提供的上下文（如有）回答用户问题，避免过度发挥。

            ## 执行步骤
            1. 理解用户问题的核心。
            2. 如有上下文，优先基于上下文回答。
            3. 组织简洁、准确的回答。

            ## 输出格式
            - 直接输出回答内容
            - 不需要问候语或总结

            ## 约束条件
            - 简洁准确，避免过度发挥
            - 如上下文不足，明确说明
            - 不使用空洞的套话
            """;

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
    }
}
