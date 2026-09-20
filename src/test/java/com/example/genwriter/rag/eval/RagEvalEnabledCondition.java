package com.example.genwriter.rag.eval;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * RAG 评测的启用条件，在 Spring 上下文加载 <b>之前</b> 生效。
 *
 * <p>启用需要同时满足两个条件：
 * <ol>
 *   <li>开关打开：环境变量 {@code RAG_EVAL=1}，或系统属性 {@code -Drag.eval=1}；</li>
 *   <li>存在 {@code DASHSCOPE_API_KEY}（向量化必须调用真实 embedding API）。</li>
 * </ol>
 *
 * <p>之所以用 {@link ExecutionCondition} 而不是仅在 {@code @BeforeAll} 里做 assumption：
 * 条件是容器级别判定，未启用时 <b>根本不会创建 Spring 上下文</b>。
 * 这样日常 {@code mvn test} 既不依赖 PostgreSQL/Redis，也不会为评测类付出上下文启动耗时，
 * 而开关打开时仍然走完整的真实链路。
 */
final class RagEvalEnabledCondition implements ExecutionCondition {

    static final String SWITCH_ENV = "RAG_EVAL";
    static final String SWITCH_PROPERTY = "rag.eval";
    static final String API_KEY_ENV = "DASHSCOPE_API_KEY";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (!isSwitchOn()) {
            return ConditionEvaluationResult.disabled("未启用 RAG 评测：设置环境变量 " + SWITCH_ENV
                    + "=1 或系统属性 -D" + SWITCH_PROPERTY + "=1 后重试");
        }
        String apiKey = System.getenv(API_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            return ConditionEvaluationResult.disabled("未启用 RAG 评测：缺少环境变量 " + API_KEY_ENV);
        }
        return ConditionEvaluationResult.enabled("RAG 评测已启用");
    }

    /** 开关是否打开（不含 API Key 判定）。 */
    static boolean isSwitchOn() {
        return "1".equals(System.getenv(SWITCH_ENV)) || "1".equals(System.getProperty(SWITCH_PROPERTY));
    }
}
