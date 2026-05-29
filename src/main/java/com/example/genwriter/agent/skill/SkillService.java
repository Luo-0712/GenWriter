package com.example.genwriter.agent.skill;

import com.example.genwriter.model.vo.SkillVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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

    // ---- CRUD ----

    public SkillVO createSkill(String name, String displayName, String description,
                               String category, List<String> tags, String content,
                               Boolean disableModelInvocation, Boolean userInvocable,
                               List<String> allowedTools, String argumentHint) {
        SkillValidator.validateForCreate(name, description, content, category);

        if (skillLoader.get(name) != null) {
            throw new IllegalArgumentException("技能名称 '" + name + "' 已存在");
        }

        String safeName = (displayName != null && !displayName.isBlank()) ? displayName : name;
        String safeCategory = (category != null && !category.isBlank()) ? category : "writing";
        List<String> safeTags = (tags != null) ? tags : List.of();
        String version = "1.0.0";

        String markdown = SkillLoader.generateMarkdown(name, safeName, description, safeCategory, safeTags, version, content,
                disableModelInvocation, userInvocable, allowedTools, argumentHint);

        Path filePath = skillLoader.getSkillsDir().resolve(name + ".md");
        try {
            Files.createDirectories(skillLoader.getSkillsDir());
            Files.writeString(filePath, markdown);
        } catch (IOException e) {
            throw new RuntimeException("创建技能文件失败: " + e.getMessage(), e);
        }

        skillLoader.reload();
        enabledState.putIfAbsent(name, true);
        saveState();

        log.info("[SkillService] 创建技能: {}", name);
        return getSkill(name);
    }

    public SkillVO updateSkill(String name, String displayName, String description,
                               String category, List<String> tags, String content, Boolean enabled,
                               Boolean disableModelInvocation, Boolean userInvocable,
                               List<String> allowedTools, String argumentHint) {
        SkillResource existing = skillLoader.get(name);
        if (existing == null) {
            throw new IllegalArgumentException("未找到名为 '" + name + "' 的技能");
        }

        SkillValidator.validateForUpdate(description, content, category);

        String newDisplayName = (displayName != null) ? displayName : existing.getDisplayName();
        String newDescription = (description != null) ? description : existing.getDescription();
        String newCategory = (category != null) ? category : existing.getCategory();
        List<String> newTags = (tags != null) ? tags : existing.getTags();
        String newContent = (content != null) ? content : existing.getContent();
        String version = existing.getVersion();
        Boolean newDisableModelInvocation = (disableModelInvocation != null) ? disableModelInvocation : existing.getDisableModelInvocation();
        Boolean newUserInvocable = (userInvocable != null) ? userInvocable : existing.getUserInvocable();
        List<String> newAllowedTools = (allowedTools != null) ? allowedTools : existing.getAllowedTools();
        String newArgumentHint = (argumentHint != null) ? argumentHint : existing.getArgumentHint();

        String markdown = SkillLoader.generateMarkdown(name, newDisplayName, newDescription, newCategory, newTags, version, newContent,
                newDisableModelInvocation, newUserInvocable, newAllowedTools, newArgumentHint);

        Path filePath = skillLoader.getSkillsDir().resolve(name + ".md");
        try {
            Files.writeString(filePath, markdown);
        } catch (IOException e) {
            throw new RuntimeException("更新技能文件失败: " + e.getMessage(), e);
        }

        skillLoader.reload();

        if (enabled != null) {
            enabledState.put(name, enabled);
            saveState();
        }

        log.info("[SkillService] 更新技能: {}", name);
        return getSkill(name);
    }

    public void deleteSkill(String name) {
        SkillResource existing = skillLoader.get(name);
        if (existing == null) {
            throw new IllegalArgumentException("未找到名为 '" + name + "' 的技能");
        }

        Path filePath = skillLoader.getSkillsDir().resolve(existing.getSourceFile());
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            throw new RuntimeException("删除技能文件失败: " + e.getMessage(), e);
        }

        skillLoader.reload();
        enabledState.remove(name);
        saveState();

        log.info("[SkillService] 删除技能: {}", name);
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
                .sourceFile(skill.getSourceFile())
                .disableModelInvocation(skill.getDisableModelInvocation())
                .userInvocable(skill.getUserInvocable())
                .allowedTools(skill.getAllowedTools())
                .argumentHint(skill.getArgumentHint())
                .build();
    }
}
