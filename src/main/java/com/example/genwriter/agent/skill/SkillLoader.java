package com.example.genwriter.agent.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SkillLoader {

    @Value("${genwriter.skills.external-dir:}")
    private String externalDir;

    private final Map<String, SkillResource> allSkills = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadAll() {
        loadFromClasspath();
        loadFromExternalDir();
        log.info("[SkillLoader] 已加载 {} 个 skill", allSkills.size());
    }

    private void loadFromClasspath() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/*.md");
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    String filename = resource.getFilename();
                    SkillResource skill = parse(is, filename, true);
                    if (skill != null) {
                        allSkills.put(skill.getName(), skill);
                        log.debug("[SkillLoader] 加载内置 skill: {}", skill.getName());
                    }
                } catch (Exception e) {
                    log.warn("[SkillLoader] 解析内置 skill 失败: {}", resource.getFilename(), e);
                }
            }
        } catch (IOException e) {
            log.debug("[SkillLoader] 未找到内置 skill 文件");
        }
    }

    private void loadFromExternalDir() {
        if (externalDir == null || externalDir.isBlank()) return;

        Path dir = Paths.get(externalDir);
        if (!Files.isDirectory(dir)) {
            log.debug("[SkillLoader] 外部 skill 目录不存在: {}", externalDir);
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path file : stream) {
                try (InputStream is = Files.newInputStream(file)) {
                    SkillResource skill = parse(is, file.getFileName().toString(), false);
                    if (skill != null) {
                        allSkills.put(skill.getName(), skill);
                        log.debug("[SkillLoader] 加载外部 skill: {}", skill.getName());
                    }
                } catch (Exception e) {
                    log.warn("[SkillLoader] 解析外部 skill 失败: {}", file, e);
                }
            }
        } catch (IOException e) {
            log.warn("[SkillLoader] 读取外部 skill 目录失败: {}", externalDir, e);
        }
    }

    public SkillResource parse(InputStream is, String filename, boolean builtIn) throws IOException {
        String markdown = new String(is.readAllBytes(), StandardCharsets.UTF_8);
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
                .builtIn(builtIn)
                .build();
    }

    public synchronized void reload() {
        allSkills.clear();
        loadAll();
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

    private String stripExtension(String filename) {
        if (filename == null) return "unknown";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
