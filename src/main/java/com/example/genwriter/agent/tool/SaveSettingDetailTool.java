package com.example.genwriter.agent.tool;

import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.model.entity.TaskSession;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Slf4j
@Component
public class SaveSettingDetailTool implements Function<SaveSettingDetailTool.SaveSettingDetailInput, String> {

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
            return ToolResult.fail("memoryType 必须是 WORLD_SETTING、CHARACTER_PROFILE 或 FORESHADOWING 之一").toJson();
        }
        if (input.name() == null || input.name().isBlank()) {
            return ToolResult.fail("名称不能为空").toJson();
        }
        if (input.content() == null || input.content().isBlank()) {
            return ToolResult.fail("内容不能为空").toJson();
        }

        String sessionId = SessionContextHolder.get();
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[SaveSettingDetailTool] 无法获取 sessionId，跳过存储");
            return ToolResult.fail("无法获取当前会话ID").toJson();
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

            return ToolResult.ok("设定细节已保存", null,
                    Map.of("memoryType", input.memoryType(), "name", input.name())).toJson();
        } catch (Exception e) {
            log.error("[SaveSettingDetailTool] 保存失败: type={}, name={}",
                    input.memoryType(), input.name(), e);
            return ToolResult.fail("保存失败: " + e.getMessage()).toJson();
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

}
