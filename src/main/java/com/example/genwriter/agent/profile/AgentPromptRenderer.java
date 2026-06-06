package com.example.genwriter.agent.profile;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AgentPromptRenderer {

    private static final Pattern IF_BLOCK =
            Pattern.compile("\\{\\{#if\\s+([A-Za-z0-9_.-]+)\\s*}}([\\s\\S]*?)\\{\\{/if}}");
    private static final Pattern VARIABLE =
            Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private final AgentProfileLoader profileLoader;

    public RenderedAgentPrompt render(String agent, Map<String, Object> context) {
        AgentProfileDefinition profile = profileLoader.get(agent);
        if (profile == null) {
            throw new IllegalArgumentException("未找到 Agent Profile: " + agent);
        }

        String systemPrompt = joinSections(
                renderTemplate(profile.role(), context),
                renderTemplate(profile.systemPrompt(), context),
                renderTemplate(profile.outputContract(), context),
                renderTemplate(profile.genreGuards(), context)
        );
        String userPrompt = renderTemplate(profile.userPromptTemplate(), context);
        return new RenderedAgentPrompt(systemPrompt, userPrompt, profile.outputContract());
    }

    String renderTemplate(String template, Map<String, Object> context) {
        if (template == null || template.isBlank()) {
            return "";
        }

        String withConditionals = renderConditionals(template, context != null ? context : Map.of());
        Matcher matcher = VARIABLE.matcher(withConditionals);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object value = context != null ? context.get(matcher.group(1)) : null;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value != null ? String.valueOf(value) : ""));
        }
        matcher.appendTail(sb);
        return sb.toString().strip();
    }

    private String renderConditionals(String template, Map<String, Object> context) {
        String result = template;
        boolean changed;
        do {
            changed = false;
            Matcher matcher = IF_BLOCK.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                changed = true;
                Object value = context.get(matcher.group(1));
                matcher.appendReplacement(sb, Matcher.quoteReplacement(isTruthy(value) ? matcher.group(2) : ""));
            }
            matcher.appendTail(sb);
            result = sb.toString();
        } while (changed && IF_BLOCK.matcher(result).find());
        return result;
    }

    private boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof CharSequence s) return !s.toString().isBlank();
        if (value instanceof Collection<?> c) return !c.isEmpty();
        if (value instanceof Map<?, ?> m) return !m.isEmpty();
        if (value instanceof Number n) return n.doubleValue() != 0.0;
        return true;
    }

    private String joinSections(String... sections) {
        StringBuilder sb = new StringBuilder();
        for (String section : sections) {
            if (section == null || section.isBlank()) continue;
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(section.strip());
        }
        return sb.toString();
    }
}
