package com.example.genwriter.agent.skill;

import java.util.LinkedHashMap;
import java.util.Map;

public class MarkdownFrontmatterParser {

    public record Result(Map<String, Object> metadata, String body) {}

    public static Result parse(String markdown) {
        if (markdown == null || !markdown.strip().startsWith("---")) {
            return new Result(Map.of(), markdown);
        }

        String trimmed = markdown.strip();
        int secondFence = trimmed.indexOf("---", 3);
        if (secondFence < 0) {
            return new Result(Map.of(), markdown);
        }

        String yamlBlock = trimmed.substring(3, secondFence).strip();
        String body = trimmed.substring(secondFence + 3).strip();

        Map<String, Object> metadata = parseSimpleYaml(yamlBlock);
        return new Result(metadata, body);
    }

    private static Map<String, Object> parseSimpleYaml(String yaml) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String line : yaml.split("\\n")) {
            String trimmedLine = line.strip();
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) continue;

            int colonIdx = trimmedLine.indexOf(':');
            if (colonIdx < 0) continue;

            String key = trimmedLine.substring(0, colonIdx).strip();
            String value = trimmedLine.substring(colonIdx + 1).strip();

            if (value.startsWith("[") && value.endsWith("]")) {
                result.put(key, parseYamlArray(value));
            } else {
                String unquoted = unquote(value);
                result.put(key, unquoted);
            }
        }
        return result;
    }

    private static java.util.List<String> parseYamlArray(String arrayStr) {
        String inner = arrayStr.substring(1, arrayStr.length() - 1).strip();
        if (inner.isEmpty()) return java.util.List.of();
        java.util.List<String> items = new java.util.ArrayList<>();
        for (String item : inner.split(",")) {
            items.add(unquote(item.strip()));
        }
        return items;
    }

    private static String unquote(String s) {
        if (s == null) return "";
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
