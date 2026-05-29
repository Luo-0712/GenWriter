package com.example.genwriter.agent.skill;

import java.util.List;
import java.util.regex.Pattern;

public class SkillValidator {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,98}[a-z0-9]$");
    private static final List<String> VALID_CATEGORIES = List.of("writing", "research", "workflow", "style");

    public static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("技能名称不能为空");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("技能名称格式无效：仅允许小写字母、数字和连字符，3-100 个字符，以字母开头");
        }
    }

    public static void validateDescription(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("技能描述不能为空");
        }
        if (description.length() < 5) {
            throw new IllegalArgumentException("技能描述至少 5 个字符");
        }
        if (description.length() > 500) {
            throw new IllegalArgumentException("技能描述最多 500 个字符");
        }
    }

    public static void validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("技能内容不能为空");
        }
        if (content.length() < 20) {
            throw new IllegalArgumentException("技能内容至少 20 个字符");
        }
    }

    public static void validateCategory(String category) {
        if (category != null && !category.isBlank() && !VALID_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("无效的分类: " + category + "，可选值: " + String.join(", ", VALID_CATEGORIES));
        }
    }

    public static void validateForCreate(String name, String description, String content, String category) {
        validateName(name);
        validateDescription(description);
        validateContent(content);
        validateCategory(category);
    }

    public static void validateForUpdate(String description, String content, String category) {
        if (description != null) validateDescription(description);
        if (content != null) validateContent(content);
        if (category != null) validateCategory(category);
    }
}
