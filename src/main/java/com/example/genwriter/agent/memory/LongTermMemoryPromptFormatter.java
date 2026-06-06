package com.example.genwriter.agent.memory;

import com.example.genwriter.model.dto.response.MemoryVO;
import com.example.genwriter.model.enums.MemoryType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class LongTermMemoryPromptFormatter {

    private static final Map<String, String> TYPE_LABELS = new LinkedHashMap<>();
    private static final List<String> TYPE_ORDER = List.of(
            MemoryType.WRITING_PREFERENCE.name(),
            MemoryType.CORRECTION_PATTERN.name(),
            MemoryType.WRITING_TECHNIQUE.name(),
            MemoryType.WORLD_SETTING.name(),
            MemoryType.CHARACTER_PROFILE.name(),
            MemoryType.FORESHADOWING.name(),
            MemoryType.DOMAIN_KNOWLEDGE.name()
    );

    static {
        TYPE_LABELS.put(MemoryType.WRITING_PREFERENCE.name(), "写作偏好");
        TYPE_LABELS.put(MemoryType.CORRECTION_PATTERN.name(), "纠错模式");
        TYPE_LABELS.put(MemoryType.WRITING_TECHNIQUE.name(), "写作技巧");
        TYPE_LABELS.put(MemoryType.WORLD_SETTING.name(), "世界观设定");
        TYPE_LABELS.put(MemoryType.CHARACTER_PROFILE.name(), "人物设定");
        TYPE_LABELS.put(MemoryType.FORESHADOWING.name(), "伏笔与呼应");
        TYPE_LABELS.put(MemoryType.DOMAIN_KNOWLEDGE.name(), "领域知识");
    }

    private final LongTermMemoryMetadataSupport metadataSupport;

    public LongTermMemoryPromptFormatter(LongTermMemoryMetadataSupport metadataSupport) {
        this.metadataSupport = metadataSupport;
    }

    public String format(List<MemoryVO> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        Map<String, List<MemoryVO>> grouped = new LinkedHashMap<>();
        for (String type : TYPE_ORDER) {
            grouped.put(type, new ArrayList<>());
        }
        for (MemoryVO memory : memories) {
            if (memory == null) {
                continue;
            }
            grouped.computeIfAbsent(memory.getMemoryType(), key -> new ArrayList<>()).add(memory);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[长期记忆]\n");
        sb.append("以下信息来自历史交互，用于保持写作偏好、技巧和设定一致；如果与本轮用户明确要求冲突，以本轮要求为准。\n");

        for (Map.Entry<String, List<MemoryVO>> entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            sb.append("\n## ").append(TYPE_LABELS.getOrDefault(entry.getKey(), entry.getKey())).append("\n");
            int index = 1;
            for (MemoryVO memory : entry.getValue()) {
                sb.append(index++).append(". ").append(formatOne(memory)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String formatOne(MemoryVO memory) {
        Map<String, Object> metadata = metadataSupport.toMap(memory.getMetadata());
        Map<String, Object> facets = metadataSupport.toMap(metadata.get("facets"));

        String title = firstNonBlank(
                asString(metadata.get("title")),
                asString(facets.get("skillName")),
                asString(facets.get("name")),
                inferTitle(memory.getContent(), memory.getMemoryType())
        );
        String detail = detailFor(memory.getMemoryType(), metadata, facets, memory.getContent());

        List<String> parts = new ArrayList<>();
        StringBuilder main = new StringBuilder();
        if (title != null && !title.isBlank()) {
            main.append("[").append(title).append("]");
        }
        if (detail != null && !detail.isBlank()) {
            if (!main.isEmpty()) {
                main.append(" ");
            }
            main.append(detail);
        }
        if (!main.isEmpty()) {
            parts.add(main.toString());
        }

        String scope = "PROJECT".equals(memory.getScope()) ? "项目" : "全局";
        parts.add("重要度:" + firstNonBlank(memory.getImportance(), "MEDIUM"));
        parts.add("范围:" + scope);

        return String.join("；", parts);
    }

    private String detailFor(String memoryType,
                             Map<String, Object> metadata,
                             Map<String, Object> facets,
                             String content) {
        String detail = switch (memoryType) {
            case "WRITING_TECHNIQUE" -> firstNonBlank(
                    asString(facets.get("rule")),
                    asString(metadata.get("summary")),
                    summarize(content)
            );
            case "WRITING_PREFERENCE" -> firstNonBlank(
                    asString(facets.get("preference")),
                    asString(metadata.get("summary")),
                    summarize(content)
            );
            case "CORRECTION_PATTERN" -> firstNonBlank(
                    asString(facets.get("pattern")),
                    asString(metadata.get("summary")),
                    summarize(content)
            );
            case "WORLD_SETTING", "CHARACTER_PROFILE", "FORESHADOWING" -> firstNonBlank(
                    asString(facets.get("details")),
                    asString(metadata.get("summary")),
                    summarize(content)
            );
            case "DOMAIN_KNOWLEDGE" -> firstNonBlank(
                    asString(facets.get("knowledge")),
                    asString(metadata.get("summary")),
                    summarize(content)
            );
            default -> firstNonBlank(asString(metadata.get("summary")), summarize(content));
        };

        String applicableScene = asString(facets.get("applicableScene"));
        if (applicableScene != null && !applicableScene.isBlank()
                && MemoryType.WRITING_TECHNIQUE.name().equals(memoryType)) {
            detail = firstNonBlank(detail, "") + "（适用：" + truncate(applicableScene, 80) + "）";
        }

        return truncate(detail, 260);
    }

    private String inferTitle(String content, String memoryType) {
        String summary = summarize(content);
        if (summary == null || summary.isBlank()) {
            return memoryType;
        }
        return truncate(summary, 36);
    }

    private String summarize(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        return content
                .replaceAll("(?m)^##\\s+", "")
                .replaceAll("\\R+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : Objects.toString(value, null);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)) + "...";
    }
}
