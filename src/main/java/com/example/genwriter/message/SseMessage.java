package com.example.genwriter.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE (Server-Sent Events) 标准消息响应载荷
 * 用于规范化大模型流式输出时，后端向前端推送的数据结构。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SseMessage {

    /**
     * 消息类型，告诉前端当前这个数据包是用来干什么的
     */
    private Type type;

    /**
     * 核心负载，包含实际的对话内容或状态文本
     */
    private Payload payload;

    /**
     * 元数据，通常用于携带一些上下文 ID
     */
    private Metadata metadata;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Payload {
        /**
         * 完整的或增量的消息视图对象
         */
        private Object data;

        /**
         * 状态文本，例如："正在检索知识库..."、"正在生成大纲..."
         */
        private String statusText;

        /**
         * 标识当前整个对话流是否已经完全结束
         */
        private Boolean done;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Metadata {
        /**
         * 对应数据库表中的主键 ID
         */
        private String resourceId;
    }

    /**
     * 预定义的消息类型枚举
     * 这些类型帮助前端的 React 组件决定如何渲染当前的流数据
     */
    public enum Type {
        /**
         * AI 生成的实际对话内容（Markdown 文本）
         */
        AI_GENERATED_CONTENT,

        /**
         * AI 正在规划任务（通常在复杂的 Agent 场景出现）
         */
        AI_PLANNING,

        /**
         * AI 正在思考（如 DeepSeek-R1 的 CoT 思考过程）
         */
        AI_THINKING,

        /**
         * AI 正在执行某个工具（Function Calling）
         */
        AI_EXECUTING,

        AI_CHAIN_EVENT,

        /**
         * 结构化执行轨迹事件，用于展示子智能体、工具调用和关键工作步骤。
         */
        AI_TRACE_EVENT,

        AI_DONE,

        /**
         * 会话标题已更新（由 LLM 智能生成）
         */
        TITLE_UPDATED,

        /**
         * 附件处理状态更新（文本提取、缩略图生成等）
         */
        ATTACHMENT_PROCESSING,

        /**
         * 错误信息
         */
        ERROR,
    }
}
