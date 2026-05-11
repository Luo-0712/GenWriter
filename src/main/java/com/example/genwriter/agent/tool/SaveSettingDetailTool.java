package com.example.genwriter.agent.tool;

import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.model.entity.TaskSession;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.function.Function;

@Slf4j
@Component
public class SaveSettingDetailTool implements Function<SaveSettingDetailTool.SaveSettingDetailInput, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> VALID_TYPES = Set.of(
            "WORLD_SETTING", "CHARACTER_PROFILE", "FORESHADOWING"
    );

    private final LongTermMemoryService memoryService;
    private final TaskSessionMapper taskSessionMapper;

    public record SaveSettingDetailInput(
            String memoryType,
            String name,
            String content,
            String importance
    ) {
    }

    public SaveSettingDetailTool(LongTermMemoryService memoryService,
                                 TaskSessionMapper taskSessionMapper) {
        this.memoryService = memoryService;
        this.taskSessionMapper = taskSessionMapper;
    }

    @Override
    public String apply(SaveSettingDetailInput input) {
        if (input.memoryType() == null || !VALID_TYPES.contains(input.memoryType())) {
            return "{\"error\": \"memoryType 必须是 WORLD_SETTING、CHARACTER_PROFILE 或 FORESHADOWING 之一\"}";
        }
        if (input.name() == null || input.name().isBlank()) {
            return "{\"error\": \"名称不能为空\"}";
        }
        if (input.content() == null || input.content().isBlank()) {
            return "{\"error\": \"内容不能为空\"}";
        }

        String sessionId = SessionContextHolder.get();
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[SaveSettingDetailTool] 无法获取 sessionId，跳过存储");
            return "{\"error\": \"无法获取当前会话ID\"}";
        }

        log.info("[SaveSettingDetailTool] 保存设定细节: type={}, name={}, sessionId={}",
                input.memoryType(), input.name(), sessionId);

        try {
            String content = buildContent(input);
            String projectId = resolveProjectId(sessionId);
            String scope = projectId != null && !projectId.isBlank() ? "PROJECT" : "GLOBAL";
            String importance = input.importance() != null && !input.importance().isBlank()
                    ? input.importance() : "MEDIUM";

            memoryService.storeMemory(content, MemoryType.valueOf(input.memoryType()),
                    scope, projectId, sessionId, importance);

            return OBJECT_MAPPER.writeValueAsString(
                    new ToolResult(true, "设定细节已保存", input.memoryType(), input.name()));
        } catch (Exception e) {
            log.error("[SaveSettingDetailTool] 保存失败: type={}, name={}",
                    input.memoryType(), input.name(), e);
            return "{\"error\": \"保存失败: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private String buildContent(SaveSettingDetailInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 名称\n").append(input.name()).append("\n\n");
        sb.append("## 详情\n").append(input.content());
        return sb.toString().trim();
    }

    private String resolveProjectId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            TaskSession session = taskSessionMapper.selectById(sessionId);
            return session != null ? session.getProjectId() : null;
        } catch (Exception e) {
            log.debug("解析projectId失败: sessionId={}", sessionId, e);
            return null;
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private record ToolResult(boolean success, String message, String memoryType, String name) {
    }
}
