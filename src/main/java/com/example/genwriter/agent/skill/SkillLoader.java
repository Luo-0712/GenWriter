package com.example.genwriter.agent.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SkillLoader {

    private static final String BUILTIN_RESOURCE_PATTERN = "classpath:skills/*.md";

    private final Map<String, SkillResource> allSkills = new ConcurrentHashMap<>();
    private Path skillsDir;

    @PostConstruct
    public void loadAll() {
        skillsDir = resolveSkillsDir();
        seedBuiltinSkills();
        loadFromDirectory();
        log.info("[SkillLoader] 已加载 {} 个 skill，目录: {}", allSkills.size(), skillsDir);
    }

    private Path resolveSkillsDir() {
        String userHome = System.getProperty("user.home");
        return Path.of(userHome, ".genwriter", "skills");
    }

    /**
     * 首次启动时，将 classpath 中的预置 .md 文件复制到用户目录
     */
    private void seedBuiltinSkills() {
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            log.error("[SkillLoader] 创建 skills 目录失败: {}", skillsDir, e);
            return;
        }

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(BUILTIN_RESOURCE_PATTERN);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) continue;
                Path target = skillsDir.resolve(filename);
                if (Files.exists(target)) continue; // 已存在则不覆盖，用户可能已修改

                try (InputStream is = resource.getInputStream()) {
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                    log.info("[SkillLoader] 复制预置 skill: {}", filename);
                } catch (Exception e) {
                    log.warn("[SkillLoader] 复制预置 skill 失败: {}", filename, e);
                }
            }
        } catch (IOException e) {
            log.debug("[SkillLoader] 未找到预置 skill 资源");
        }
    }

    private void loadFromDirectory() {
        if (!Files.isDirectory(skillsDir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir, "*.md")) {
            for (Path file : stream) {
                try (InputStream is = Files.newInputStream(file)) {
                    SkillResource skill = parse(is, file.getFileName().toString());
                    if (skill != null) {
                        allSkills.put(skill.getName(), skill);
                    }
                } catch (Exception e) {
                    log.warn("[SkillLoader] 解析 skill 失败: {}", file, e);
                }
            }
        } catch (IOException e) {
            log.warn("[SkillLoader] 读取 skills 目录失败: {}", skillsDir, e);
        }
    }

    public SkillResource parse(InputStream is, String filename) throws IOException {
        String markdown = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        return parseMarkdown(markdown, filename);
    }

    public SkillResource parseMarkdown(String markdown, String filename) {
        MarkdownFrontmatterParser.Result result = MarkdownFrontmatterParser.parse(markdown);
        Map<String, Object> meta = result.metadata();
        String body = result.body();

        String name = (String) meta.getOrDefault("name", stripExtension(filename));
        String displayName = (String) meta.getOrDefault("displayName", name);
        String description = (String) meta.getOrDefault("description", "");
        String category = (String) meta.getOrDefault("category", "");
        String version = (String) meta.getOrDefault("version", "1.0.0");

        @SuppressWarnings("unchecked")
        List<String> tags = meta.get("tags") instanceof List
                ? (List<String>) meta.get("tags")
                : List.of();

        Boolean disableModelInvocation = meta.get("disable-model-invocation") instanceof Boolean
                ? (Boolean) meta.get("disable-model-invocation") : null;
        Boolean userInvocable = meta.get("user-invocable") instanceof Boolean
                ? (Boolean) meta.get("user-invocable") : null;
        @SuppressWarnings("unchecked")
        List<String> allowedTools = meta.get("allowed-tools") instanceof List
                ? (List<String>) meta.get("allowed-tools") : null;
        String argumentHint = meta.get("argument-hint") instanceof String
                ? (String) meta.get("argument-hint") : null;

        String contentPreview = body.length() <= 200 ? body : body.substring(0, 200) + "...";

        return SkillResource.builder()
                .name(name)
                .displayName(displayName)
                .description(description)
                .category(category)
                .tags(tags)
                .version(version)
                .content(body)
                .contentPreview(contentPreview)
                .sourceFile(filename)
                .disableModelInvocation(disableModelInvocation)
                .userInvocable(userInvocable)
                .allowedTools(allowedTools)
                .argumentHint(argumentHint)
                .build();
    }

    /**
     * 生成完整的 markdown 内容（含 frontmatter）
     */
    public static String generateMarkdown(String name, String displayName, String description,
                                          String category, List<String> tags, String version, String content) {
        return generateMarkdown(name, displayName, description, category, tags, version, content, null, null, null, null);
    }

    /**
     * 生成完整的 markdown 内容（含 frontmatter），支持额外的 SKILL.md 元数据字段
     */
    public static String generateMarkdown(String name, String displayName, String description,
                                          String category, List<String> tags, String version, String content,
                                          Boolean disableModelInvocation, Boolean userInvocable,
                                          List<String> allowedTools, String argumentHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append("\n");
        sb.append("displayName: ").append(displayName).append("\n");
        sb.append("description: ").append(description).append("\n");
        sb.append("category: ").append(category).append("\n");
        sb.append("tags: [").append(String.join(", ", tags)).append("]\n");
        sb.append("version: \"").append(version != null ? version : "1.0.0").append("\"\n");
        if (disableModelInvocation != null) {
            sb.append("disable-model-invocation: ").append(disableModelInvocation).append("\n");
        }
        if (userInvocable != null) {
            sb.append("user-invocable: ").append(userInvocable).append("\n");
        }
        if (allowedTools != null && !allowedTools.isEmpty()) {
            sb.append("allowed-tools: [").append(String.join(", ", allowedTools)).append("]\n");
        }
        if (argumentHint != null && !argumentHint.isBlank()) {
            sb.append("argument-hint: ").append(argumentHint).append("\n");
        }
        sb.append("---\n\n");
        sb.append(content);
        return sb.toString();
    }

    public synchronized void reload() {
        allSkills.clear();
        loadFromDirectory();
    }

    public SkillResource get(String name) {
        return allSkills.get(name);
    }

    public List<SkillResource> getAll() {
        return new ArrayList<>(allSkills.values());
    }

    public List<SkillResource> getByCategory(String category) {
        return allSkills.values().stream()
                .filter(s -> category.equals(s.getCategory()))
                .collect(Collectors.toList());
    }

    public Path getSkillsDir() {
        return skillsDir;
    }

    private String stripExtension(String filename) {
        if (filename == null) return "unknown";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
