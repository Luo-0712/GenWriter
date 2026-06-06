package com.example.genwriter.agent.profile;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class AgentProfileLoader {

    private static final String BUILTIN_RESOURCE_PATTERN =
            "classpath*:com/example/genwriter/agent/profile/definitions/*.md";

    private final Map<String, AgentProfileDefinition> profiles = new ConcurrentHashMap<>();
    private final Path externalProfilesDir;

    public AgentProfileLoader() {
        this(null);
    }

    AgentProfileLoader(Path externalProfilesDir) {
        this.externalProfilesDir = externalProfilesDir;
    }

    @PostConstruct
    public void loadAll() {
        profiles.clear();
        loadBuiltins();
        loadOverrides();
        log.info("[AgentProfileLoader] 已加载 {} 个 Agent Profile", profiles.size());
    }

    public AgentProfileDefinition get(String agent) {
        return profiles.get(agent);
    }

    public Map<String, AgentProfileDefinition> getAll() {
        return Map.copyOf(profiles);
    }

    AgentProfileDefinition parseMarkdown(String markdown, String source) {
        Frontmatter frontmatter = parseFrontmatter(markdown);
        Map<String, Object> metadata = frontmatter.metadata();
        Map<String, String> sections = parseSections(frontmatter.body());

        String name = stringValue(metadata.get("name"), stripExtension(source));
        String agent = stringValue(metadata.get("agent"), name);
        String displayName = stringValue(metadata.get("displayName"), agent);
        String description = stringValue(metadata.get("description"), "");
        String version = stringValue(metadata.get("version"), "1.0.0");

        return new AgentProfileDefinition(
                name,
                agent,
                displayName,
                description,
                version,
                sections.getOrDefault("Role", ""),
                sections.getOrDefault("System Prompt", ""),
                sections.getOrDefault("User Prompt Template", ""),
                sections.getOrDefault("Output Contract", ""),
                sections.getOrDefault("Genre Guards", ""),
                source,
                metadata
        );
    }

    private void loadBuiltins() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(BUILTIN_RESOURCE_PATTERN);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) continue;
                try (InputStream is = resource.getInputStream()) {
                    String markdown = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    AgentProfileDefinition profile = parseMarkdown(markdown, filename);
                    profiles.put(profile.agent(), profile);
                } catch (Exception e) {
                    log.warn("[AgentProfileLoader] 解析内置 Agent Profile 失败: {}", filename, e);
                }
            }
        } catch (IOException e) {
            log.warn("[AgentProfileLoader] 扫描内置 Agent Profile 失败", e);
        }
    }

    private void loadOverrides() {
        Path dir = resolveExternalProfilesDir();
        if (!Files.isDirectory(dir)) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path file : stream) {
                try {
                    String markdown = Files.readString(file, StandardCharsets.UTF_8);
                    AgentProfileDefinition profile = parseMarkdown(markdown, file.getFileName().toString());
                    profiles.put(profile.agent(), profile);
                    log.info("[AgentProfileLoader] 已覆盖 Agent Profile: {} ({})", profile.agent(), file);
                } catch (Exception e) {
                    log.warn("[AgentProfileLoader] 解析外部 Agent Profile 失败: {}", file, e);
                }
            }
        } catch (IOException e) {
            log.warn("[AgentProfileLoader] 读取外部 Agent Profile 目录失败: {}", dir, e);
        }
    }

    private Path resolveExternalProfilesDir() {
        if (externalProfilesDir != null) {
            return externalProfilesDir;
        }
        return Path.of(System.getProperty("user.home"), ".genwriter", "agent-profiles");
    }

    private Frontmatter parseFrontmatter(String markdown) {
        if (markdown == null || !markdown.stripLeading().startsWith("---")) {
            return new Frontmatter(Map.of(), markdown != null ? markdown : "");
        }

        String text = markdown.stripLeading();
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return new Frontmatter(Map.of(), markdown);
        }

        String yamlText = text.substring(3, end).strip();
        String body = text.substring(end + 4).strip();
        Object loaded = new Yaml().load(yamlText);
        if (!(loaded instanceof Map<?, ?> loadedMap)) {
            return new Frontmatter(Map.of(), body);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : loadedMap.entrySet()) {
            if (entry.getKey() != null) {
                metadata.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return new Frontmatter(metadata, body);
    }

    private Map<String, String> parseSections(String body) {
        Map<String, StringBuilder> builders = new LinkedHashMap<>();
        String current = null;
        for (String line : body.split("\\R", -1)) {
            if (line.startsWith("## ") && isProfileSection(line.substring(3).strip())) {
                current = line.substring(3).strip();
                builders.putIfAbsent(current, new StringBuilder());
            } else if (current != null) {
                builders.get(current).append(line).append("\n");
            }
        }

        Map<String, String> sections = new LinkedHashMap<>();
        for (Map.Entry<String, StringBuilder> entry : builders.entrySet()) {
            sections.put(entry.getKey(), entry.getValue().toString().strip());
        }
        return sections;
    }

    private boolean isProfileSection(String name) {
        return "Role".equals(name)
                || "System Prompt".equals(name)
                || "User Prompt Template".equals(name)
                || "Output Contract".equals(name)
                || "Genre Guards".equals(name);
    }

    private String stripExtension(String source) {
        if (source == null) return "unknown";
        int dot = source.lastIndexOf('.');
        return dot > 0 ? source.substring(0, dot) : source;
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private record Frontmatter(Map<String, Object> metadata, String body) {
    }
}
