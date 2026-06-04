package com.example.genwriter.agent.tool;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
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
    private final ThoughtChainPublisher chainPublisher;

    public record SaveSettingDetailInput(
            String memoryType,
            String name,
            String content,
            String importance
    ) {
    }

    public SaveSettingDetailTool(LongTermMemoryService memoryService,
                                 TaskSessionMapper taskSessionMapper,
                                 ThoughtChainPublisher chainPublisher) {
        this.memoryService = memoryService;
        this.taskSessionMapper = taskSessionMapper;
        this.chainPublisher = chainPublisher;
    }

    @Override
    public String apply(SaveSettingDetailInput input) {
        String sessionId = SessionContextHolder.get();
        String traceSpanId = null;
        if (sessionId != null && !sessionId.isBlank()) {
            traceSpanId = chainPublisher.publishToolStart(sessionId, "save_setting_detail",
                    Map.of("memoryType", input != null ? safe(input.memoryType()) : "",
                            "name", input != null ? safe(input.name()) : "",
                            "importance", input != null ? safe(input.importance()) : ""));
        }

        if (input == null || input.memoryType() == null || !VALID_TYPES.contains(input.memoryType())) {
            publishToolError(sessionId, traceSpanId, "memoryType 无效");
            return ToolResult.fail("memoryType 必须是 WORLD_SETTING、CHARACTER_PROFILE 或 FORESHADOWING 之一").toJson();
        }
        if (input.name() == null || input.name().isBlank()) {
            publishToolError(sessionId, traceSpanId, "名称不能为空");
            return ToolResult.fail("名称不能为空").toJson();
        }
        if (input.content() == null || input.content().isBlank()) {
            publishToolError(sessionId, traceSpanId, "内容不能为空");
            return ToolResult.fail("内容不能为空").toJson();
        }

        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[SaveSettingDetailTool] 无法获取 sessionId，跳过存储");
            publishToolError(sessionId, traceSpanId, "无法获取当前会话ID");
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

            publishToolComplete(sessionId, traceSpanId, Map.of(
                    "memoryType", input.memoryType(),
                    "name", input.name(),
                    "scope", scope,
                    "importance", importance
            ));
            return ToolResult.ok("设定细节已保存", null,
                    Map.of("memoryType", input.memoryType(), "name", input.name())).toJson();
        } catch (Exception e) {
            log.error("[SaveSettingDetailTool] 保存失败: type={}, name={}",
                    input.memoryType(), input.name(), e);
            publishToolError(sessionId, traceSpanId, e.getMessage());
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

    private void publishToolComplete(String sessionId, String traceSpanId, Object output) {
        if (sessionId == null || sessionId.isBlank() || traceSpanId == null) return;
        chainPublisher.publishToolComplete(sessionId, traceSpanId, "save_setting_detail", output);
    }

    private void publishToolError(String sessionId, String traceSpanId, String error) {
        if (sessionId == null || sessionId.isBlank() || traceSpanId == null) return;
        chainPublisher.publishToolError(sessionId, traceSpanId, "save_setting_detail", error);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

}
