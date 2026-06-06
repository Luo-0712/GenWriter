package com.example.genwriter.agent.memory;

import com.example.genwriter.model.enums.MemoryType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class LongTermMemoryMetadataSupport {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_SUMMARY_LENGTH = 220;
    private static final int MAX_RETRIEVAL_FIELD_LENGTH = 600;
    private static final Set<String> SETTING_TYPES = Set.of(
            MemoryType.WORLD_SETTING.name(),
            MemoryType.CHARACTER_PROFILE.name(),
            MemoryType.FORESHADOWING.name()
    );

    private final ObjectMapper objectMapper;

    public LongTermMemoryMetadataSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> normalize(String content,
                                         String memoryType,
                                         String scope,
                                         String projectId,
                                         String sessionId,
                                         String importance,
                                         Object rawMetadata) {
        Map<String, Object> raw = toMap(rawMetadata);
        Map<String, String> sections = parseMarkdownSections(content);

        Map<String, Object> normalized = new LinkedHashMap<>(raw);
        normalized.put("schemaVersion", SCHEMA_VERSION);

        String title = firstNonBlank(
                asString(raw.get("title")),
                sections.get("技巧名称"),
                sections.get("名称"),
                sections.get("标题"),
                inferTitle(memoryType, content)
        );
        putIfPresent(normalized, "title", title);

        Map<String, Object> facets = toMap(raw.get("facets"));
        applySectionFacets(facets, sections, memoryType);
        putIfPresent(normalized, "facets", facets);

        String summary = firstNonBlank(
                asString(raw.get("summary")),
                asString(facets.get("rule")),
                asString(facets.get("details")),
                asString(facets.get("preference")),
                asString(facets.get("pattern")),
                asString(facets.get("knowledge")),
                summarize(content)
        );
        putIfPresent(normalized, "summary", summary);

        List<String> keywords = mergeStringLists(
                toStringList(raw.get("keywords")),
                deriveKeywords(title, memoryType, facets, content)
        );
        putIfPresent(normalized, "keywords", keywords);

        List<String> entities = mergeStringLists(
                toStringList(raw.get("entities")),
                deriveEntities(title, memoryType)
        );
        putIfPresent(normalized, "entities", entities);

        Map<String, Object> source = toMap(raw.get("source"));
        putIfPresent(source, "scope", scope);
        putIfPresent(source, "projectId", projectId);
        putIfPresent(source, "sessionId", sessionId);
        putIfPresent(source, "importance", importance);
        putIfPresent(normalized, "source", source);

        return removeEmptyValues(normalized);
    }

    public Map<String, Object> merge(String existingContent,
                                     String newContent,
                                     String memoryType,
                                     String scope,
                                     String projectId,
                                     String sessionId,
                                     String importance,
                                     Object existingMetadata,
                                     Object newMetadata) {
        String mergedContent = mergeText(existingContent, newContent);
        Map<String, Object> existing = normalize(existingContent, memoryType, scope, projectId, sessionId, importance, existingMetadata);
        Map<String, Object> incoming = normalize(newContent, memoryType, scope, projectId, sessionId, importance, newMetadata);

        Map<String, Object> merged = new LinkedHashMap<>(existing);
        putIfPresent(merged, "schemaVersion", SCHEMA_VERSION);
        putIfPresent(merged, "title", firstNonBlank(asString(existing.get("title")), asString(incoming.get("title"))));
        putIfPresent(merged, "summary", summarize(mergedContent));
        putIfPresent(merged, "keywords", mergeStringLists(toStringList(existing.get("keywords")), toStringList(incoming.get("keywords"))));
        putIfPresent(merged, "entities", mergeStringLists(toStringList(existing.get("entities")), toStringList(incoming.get("entities"))));

        Map<String, Object> facets = mergeFacets(toMap(existing.get("facets")), toMap(incoming.get("facets")));
        putIfPresent(merged, "facets", facets);

        Map<String, Object> source = toMap(existing.get("source"));
        putIfPresent(source, "scope", scope);
        putIfPresent(source, "projectId", projectId);
        putIfPresent(source, "sessionId", sessionId);
        putIfPresent(source, "importance", importance);
        putIfPresent(merged, "source", source);

        return removeEmptyValues(merged);
    }

    public String buildRetrievalText(String content, String memoryType, Map<String, Object> metadata) {
        StringBuilder sb = new StringBuilder();
        appendField(sb, "类型", memoryType);
        appendField(sb, "标题", asString(metadata.get("title")));
        appendField(sb, "摘要", asString(metadata.get("summary")));
        appendField(sb, "关键词", String.join(" ", toStringList(metadata.get("keywords"))));
        appendField(sb, "实体", String.join(" ", toStringList(metadata.get("entities"))));

        Map<String, Object> facets = toMap(metadata.get("facets"));
        for (Map.Entry<String, Object> entry : facets.entrySet()) {
            appendField(sb, entry.getKey(), asString(entry.getValue()));
        }

        appendField(sb, "原文", content);
        return sb.toString().trim();
    }

    public List<String> extractTextSearchTerms(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return List.of();
        }

        for (String segment : extractAlphaNumericAndHanSegments(query)) {
            addTerm(terms, segment);
            if (containsHan(segment) && segment.length() > 4) {
                addNgrams(terms, segment, 2, 8);
                addNgrams(terms, segment, 3, 6);
            }
            if (terms.size() >= 12) {
                break;
            }
        }

        return new ArrayList<>(terms).subList(0, Math.min(terms.size(), 12));
    }

    public Map<String, Object> parseMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return new LinkedHashMap<>();
        }
        return toMap(metadata);
    }

    public String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata != null ? metadata : Map.of());
        } catch (Exception e) {
            throw new IllegalArgumentException("长期记忆 metadata 序列化失败", e);
        }
    }

    public Map<String, Object> toMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        if (value instanceof String text) {
            if (text.isBlank()) {
                return new LinkedHashMap<>();
            }
            try {
                return objectMapper.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
                });
            } catch (Exception ignored) {
                return new LinkedHashMap<>();
            }
        }
        return objectMapper.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    public List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addClean(result, asString(item));
            }
        } else {
            addClean(result, asString(value));
        }
        return dedupe(result);
    }

    private void applySectionFacets(Map<String, Object> facets, Map<String, String> sections, String memoryType) {
        putIfPresentIfAbsent(facets, "skillName", sections.get("技巧名称"));
        putIfPresentIfAbsent(facets, "category", sections.get("分类"));
        putIfPresentIfAbsent(facets, "rule", sections.get("规则"));
        putIfPresentIfAbsent(facets, "applicableScene", sections.get("适用场景"));
        putIfPresentIfAbsent(facets, "goodExample", sections.get("正例"));
        putIfPresentIfAbsent(facets, "badExample", sections.get("反例"));
        putIfPresentIfAbsent(facets, "sourceContext", sections.get("来源"));

        String details = firstNonBlank(sections.get("详情"), sections.get("内容"));
        putIfPresentIfAbsent(facets, "name", firstNonBlank(sections.get("名称"), sections.get("标题")));
        putIfPresentIfAbsent(facets, "details", details);

        if (MemoryType.WRITING_PREFERENCE.name().equals(memoryType)) {
            putIfPresentIfAbsent(facets, "preference", summarize(details != null ? details : sectionsToText(sections)));
        } else if (MemoryType.CORRECTION_PATTERN.name().equals(memoryType)) {
            putIfPresentIfAbsent(facets, "pattern", summarize(details != null ? details : sectionsToText(sections)));
        } else if (MemoryType.DOMAIN_KNOWLEDGE.name().equals(memoryType)) {
            putIfPresentIfAbsent(facets, "knowledge", summarize(details != null ? details : sectionsToText(sections)));
        }
    }

    private Map<String, Object> mergeFacets(Map<String, Object> existing, Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String key = entry.getKey();
            String oldValue = asString(merged.get(key));
            String newValue = asString(entry.getValue());
            if (newValue == null || newValue.isBlank()) {
                continue;
            }
            if (oldValue == null || oldValue.isBlank()) {
                merged.put(key, entry.getValue());
            } else if (!oldValue.contains(newValue)) {
                merged.put(key, mergeText(oldValue, newValue));
            }
        }
        return merged;
    }

    private List<String> deriveKeywords(String title, String memoryType, Map<String, Object> facets, String content) {
        List<String> keywords = new ArrayList<>();
        addClean(keywords, title);
        addClean(keywords, asString(facets.get("category")));
        addClean(keywords, asString(facets.get("skillName")));
        addClean(keywords, asString(facets.get("name")));
        if (MemoryType.WRITING_TECHNIQUE.name().equals(memoryType)) {
            addClean(keywords, "写作技巧");
        } else if (MemoryType.WRITING_PREFERENCE.name().equals(memoryType)) {
            addClean(keywords, "写作偏好");
        } else if (SETTING_TYPES.contains(memoryType)) {
            addClean(keywords, "设定");
        }
        for (String segment : extractTextSearchTerms(firstNonBlank(title, content))) {
            if (keywords.size() >= 8) {
                break;
            }
            addClean(keywords, segment);
        }
        return dedupe(keywords);
    }

    private List<String> deriveEntities(String title, String memoryType) {
        if (title == null || title.isBlank() || !SETTING_TYPES.contains(memoryType)) {
            return List.of();
        }
        return List.of(title.trim());
    }

    private Map<String, String> parseMarkdownSections(String content) {
        if (content == null || content.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, StringBuilder> builders = new LinkedHashMap<>();
        String current = null;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                current = trimmed.substring(3).trim();
                builders.putIfAbsent(current, new StringBuilder());
            } else if (current != null) {
                if (!trimmed.isBlank()) {
                    StringBuilder sb = builders.get(current);
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(trimmed);
                }
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, StringBuilder> entry : builders.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toString().trim());
        }
        return result;
    }

    private String inferTitle(String memoryType, String content) {
        String text = firstNonBlank(firstContentLine(content), summarize(content));
        if (text == null || text.isBlank()) {
            return memoryType;
        }
        return truncate(text, 40);
    }

    private String summarize(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String cleaned = content
                .replaceAll("(?m)^##\\s+", "")
                .replaceAll("\\R+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return truncate(cleaned, MAX_SUMMARY_LENGTH);
    }

    private String sectionsToText(Map<String, String> sections) {
        if (sections == null || sections.isEmpty()) {
            return null;
        }
        return String.join(" ", sections.values());
    }

    private String firstContentLine(String content) {
        if (content == null) {
            return null;
        }
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank() && !trimmed.startsWith("##")) {
                return trimmed;
            }
        }
        return null;
    }

    private List<String> extractAlphaNumericAndHanSegments(String text) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp) || isHan(cp)) {
                current.appendCodePoint(cp);
            } else {
                flushSegment(segments, current);
            }
        });
        flushSegment(segments, current);
        return segments;
    }

    private void addNgrams(Set<String> terms, String segment, int n, int maxCount) {
        int added = 0;
        for (int i = 0; i <= segment.length() - n && added < maxCount; i++) {
            String gram = segment.substring(i, i + n);
            if (addTerm(terms, gram)) {
                added++;
            }
        }
    }

    private boolean addTerm(Set<String> terms, String term) {
        String cleaned = clean(term);
        if (cleaned == null || cleaned.length() < 2 || cleaned.length() > 64) {
            return false;
        }
        terms.add(cleaned);
        return true;
    }

    private void flushSegment(List<String> segments, StringBuilder current) {
        if (!current.isEmpty()) {
            String segment = clean(current.toString());
            if (segment != null && segment.length() >= 2) {
                segments.add(segment);
            }
            current.setLength(0);
        }
    }

    private boolean containsHan(String value) {
        if (value == null) {
            return false;
        }
        return value.codePoints().anyMatch(this::isHan);
    }

    private boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private Map<String, Object> removeEmptyValues(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof String text && text.isBlank()) {
                continue;
            }
            if (value instanceof Iterable<?> iterable && !iterable.iterator().hasNext()) {
                continue;
            }
            if (value instanceof Map<?, ?> map && map.isEmpty()) {
                continue;
            }
            result.put(entry.getKey(), value);
        }
        return result;
    }

    private void appendField(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(label).append(": ").append(truncate(value, MAX_RETRIEVAL_FIELD_LENGTH)).append('\n');
    }

    private List<String> mergeStringLists(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>();
        if (first != null) {
            first.forEach(item -> addClean(merged, item));
        }
        if (second != null) {
            second.forEach(item -> addClean(merged, item));
        }
        return dedupe(merged);
    }

    private List<String> dedupe(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private void addClean(List<String> values, String value) {
        String cleaned = clean(value);
        if (cleaned != null) {
            values.add(cleaned);
        }
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String mergeText(String existing, String incoming) {
        if (existing == null || existing.isBlank()) {
            return incoming;
        }
        if (incoming == null || incoming.isBlank() || existing.contains(incoming)) {
            return existing;
        }
        return existing + "\n---\n" + incoming;
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value instanceof Iterable<?> iterable && !iterable.iterator().hasNext()) {
            return;
        }
        if (value instanceof Map<?, ?> nested && nested.isEmpty()) {
            return;
        }
        map.put(key, value);
    }

    private void putIfPresentIfAbsent(Map<String, Object> map, String key, Object value) {
        if (map.containsKey(key)) {
            return;
        }
        putIfPresent(map, key, value);
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
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> parts = new ArrayList<>();
            for (Object item : iterable) {
                String text = asString(item);
                if (text != null && !text.isBlank()) {
                    parts.add(text);
                }
            }
            return String.join(" ", parts);
        }
        return Objects.toString(value, null);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)) + "...";
    }
}
