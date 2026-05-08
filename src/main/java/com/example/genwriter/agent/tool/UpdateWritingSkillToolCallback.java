package com.example.genwriter.agent.tool;

import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.model.entity.TaskSession;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Slf4j
@Component
public class UpdateWritingSkillToolCallback implements Function<UpdateWritingSkillToolCallback.UpdateWritingSkillInput, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LongTermMemoryService memoryService;
    private final TaskSessionMapper taskSessionMapper;

    public record UpdateWritingSkillInput(
            String skillName,
            String category,
            String rule,
            String applicableScene,
            String goodExample,
            String badExample,
            String sourceContext,
            String importance
    ) {
    }

    public UpdateWritingSkillToolCallback(LongTermMemoryService memoryService,
                                          TaskSessionMapper taskSessionMapper) {
        this.memoryService = memoryService;
        this.taskSessionMapper = taskSessionMapper;
    }

    @Override
    public String apply(UpdateWritingSkillInput input) {
        if (input.skillName() == null || input.skillName().isBlank()
                || input.rule() == null || input.rule().isBlank()) {
            return "{\"error\": \"技巧名称和规则不能为空\"}";
        }

        String sessionId = SessionContextHolder.get();
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[UpdateWritingSkillTool] 无法获取 sessionId，跳过存储");
            return "{\"error\": \"无法获取当前会话ID\"}";
        }

        log.info("[UpdateWritingSkillTool] 保存写作技巧: skillName={}, sessionId={}", input.skillName(), sessionId);

        try {
            String content = buildContent(input);
            String projectId = resolveProjectId(sessionId);
            String scope = projectId != null && !projectId.isBlank() ? "PROJECT" : "GLOBAL";
            String importance = input.importance() != null && !input.importance().isBlank()
                    ? input.importance() : "MEDIUM";

            memoryService.storeMemory(content, MemoryType.WRITING_TECHNIQUE, scope, projectId, sessionId, importance);

            return OBJECT_MAPPER.writeValueAsString(new ToolResult(true, "写作技巧已保存", input.skillName()));
        } catch (Exception e) {
            log.error("[UpdateWritingSkillTool] 保存失败: skillName={}", input.skillName(), e);
            return "{\"error\": \"保存失败: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private String buildContent(UpdateWritingSkillInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 技巧名称\n").append(input.skillName()).append("\n\n");

        if (input.category() != null && !input.category().isBlank()) {
            sb.append("## 分类\n").append(input.category()).append("\n\n");
        }

        sb.append("## 规则\n").append(input.rule()).append("\n\n");

        if (input.applicableScene() != null && !input.applicableScene().isBlank()) {
            sb.append("## 适用场景\n").append(input.applicableScene()).append("\n\n");
        }

        if (input.goodExample() != null && !input.goodExample().isBlank()) {
            sb.append("## 正例\n").append(input.goodExample()).append("\n\n");
        }

        if (input.badExample() != null && !input.badExample().isBlank()) {
            sb.append("## 反例\n").append(input.badExample()).append("\n\n");
        }

        if (input.sourceContext() != null && !input.sourceContext().isBlank()) {
            sb.append("## 来源\n").append(input.sourceContext()).append("\n");
        }

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

    private record ToolResult(boolean success, String message, String skillName) {
    }
}
