package com.example.genwriter.agent.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 结构化工具返回结果
 * 所有 LLM 工具统一使用此类型构建返回值，消除各工具重复的 DTO 和 escapeJson()。
 * <p>
 * 使用示例：
 * <pre>
 *   return ToolResult.ok("搜索完成", urls, metadata).toJson();
 *   return ToolResult.fail("知识库ID不能为空").toJson();
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(
        String content,
        List<String> sources,
        Map<String, Object> metadata,
        boolean success,
        String error
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -------------------------------------------------------------------------
    // 工厂方法
    // -------------------------------------------------------------------------

    public static ToolResult ok(String content) {
        return new ToolResult(content, null, null, true, null);
    }

    public static ToolResult ok(String content, List<String> sources) {
        return new ToolResult(content, sources, null, true, null);
    }

    public static ToolResult ok(String content, List<String> sources, Map<String, Object> metadata) {
        return new ToolResult(content, sources, metadata, true, null);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(null, null, null, false, error);
    }

    // -------------------------------------------------------------------------
    // 序列化
    // -------------------------------------------------------------------------

    /**
     * 序列化为 JSON 字符串，供 Spring AI FunctionToolCallback 返回
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"Serialization failed: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
