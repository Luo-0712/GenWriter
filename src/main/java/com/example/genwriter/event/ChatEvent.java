package com.example.genwriter.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天事件
 *
 * 基于 Spring Event 机制。当用户发起聊天/写作请求时，系统会发布此事件。
 * 后台的异步监听器（@EventListener）会捕获此事件，并负责处理：
 * 1. 调用 AI 模型生成内容
 * 2. 通过 SSE 推送实时进度
 * 3. 结果持久化
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatEvent {

    /**
     * 任务会话 ID
     */
    private String sessionId;

    /**
     * 文档 ID（可选，新建时为空）
     */
    private String documentId;

    /**
     * 用户输入的原始文本内容或指令
     */
    private String userInput;

    /**
     * 知识库ID（知识库问答时使用）
     */
    private String kbId;

    /**
     * 写作类型
     */
    private WritingType type;

    /**
     * 写作类型枚举
     */
    public enum WritingType {
        /**
         * 自动识别（默认）
         */
        AUTO,

        /**
         * 新建文档
         */
        CREATE,

        /**
         * 文档续写
         */
        CONTINUE,

        /**
         * 文档润色
         */
        POLISH,

        /**
         * 基于知识库问答
         */
        KNOWLEDGE_QA
    }
}
