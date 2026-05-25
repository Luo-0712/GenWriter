package com.example.genwriter.agent.skill;

import com.example.genwriter.model.vo.SkillVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    private final SkillLoader skillLoader;
    private final ObjectMapper objectMapper;

    private final Map<String, Boolean> enabledState = new ConcurrentHashMap<>();
    private Path stateFilePath;

    @PostConstruct
    public void init() {
        stateFilePath = resolveStateFilePath();
        loadState();

        for (SkillResource skill : skillLoader.getAll()) {
            enabledState.putIfAbsent(skill.getName(), true);
        }
        saveState();
        log.info("[SkillService] 初始化完成，共 {} 个 skill", enabledState.size());
    }

    // ---- 持久化 ----

    private Path resolveStateFilePath() {
        String userHome = System.getProperty("user.home");
        return Path.of(userHome, ".genwriter", "skill_states.json");
    }

    private void loadState() {
        if (!Files.exists(stateFilePath)) return;
        try {
            String json = Files.readString(stateFilePath);
            Map<String, Boolean> loaded = objectMapper.readValue(json, new TypeReference<>() {});
            enabledState.putAll(loaded);
        } catch (Exception e) {
            log.warn("[SkillService] 加载 skill 状态失败: {}", e.getMessage());
        }
    }

    private synchronized void saveState() {
        try {
            Files.createDirectories(stateFilePath.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(enabledState);
            Files.writeString(stateFilePath, json);
        } catch (Exception e) {
            log.warn("[SkillService] 保存 skill 状态失败: {}", e.getMessage());
        }
    }

    // ---- 查询 ----

    public List<SkillVO> listSkills(String category, String tag) {
        return skillLoader.getAll().stream()
                .filter(s -> category == null || category.isBlank() || category.equals(s.getCategory()))
                .filter(s -> tag == null || tag.isBlank() || s.getTags().contains(tag))
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    public SkillVO getSkill(String name) {
        SkillResource skill = skillLoader.get(name);
        return skill != null ? toVO(skill) : null;
    }

    public boolean isEnabled(String name) {
        return enabledState.getOrDefault(name, true);
    }

    // ---- 状态切换 ----

    public void setEnabled(String name, boolean enabled) {
        if (skillLoader.get(name) == null) {
            throw new IllegalArgumentException("未找到名为 '" + name + "' 的 skill");
        }
        enabledState.put(name, enabled);
        saveState();
    }

    // ---- Supervisor 集成 ----

    public String getEnabledSkillsSummary() {
        StringBuilder sb = new StringBuilder();
        for (SkillResource skill : skillLoader.getAll()) {
            if (isEnabled(skill.getName())) {
                sb.append("- **").append(skill.getName()).append("**");
                if (!skill.getTags().isEmpty()) {
                    sb.append(" [").append(String.join(", ", skill.getTags())).append("]");
                }
                sb.append("：").append(skill.getDescription()).append("\n");
            }
        }
        return sb.toString();
    }

    public String readSkillDetail(String name) {
        SkillResource skill = skillLoader.get(name);
        if (skill == null) return "未找到名为 '" + name + "' 的 skill";
        if (!isEnabled(name)) return "skill '" + name + "' 已被禁用";
        return skill.getContent();
    }

    // ---- reload ----

    public Map<String, Object> reload() {
        int before = skillLoader.getAll().size();
        skillLoader.reload();
        int after = skillLoader.getAll().size();

        for (SkillResource skill : skillLoader.getAll()) {
            enabledState.putIfAbsent(skill.getName(), true);
        }
        saveState();

        return Map.of(
                "before", before,
                "after", after,
                "enabled", (int) enabledState.values().stream().filter(b -> b).count()
        );
    }

    // ---- 分类列表 ----

    public List<String> getCategories() {
        return skillLoader.getAll().stream()
                .map(SkillResource::getCategory)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    // ---- 转换 ----

    private SkillVO toVO(SkillResource skill) {
        return SkillVO.builder()
                .name(skill.getName())
                .displayName(skill.getDisplayName())
                .description(skill.getDescription())
                .category(skill.getCategory())
                .tags(skill.getTags())
                .version(skill.getVersion())
                .content(skill.getContent())
                .contentPreview(skill.getContentPreview())
                .enabled(isEnabled(skill.getName()))
                .builtIn(skill.isBuiltIn())
                .sourceFile(skill.getSourceFile())
                .build();
    }
}
