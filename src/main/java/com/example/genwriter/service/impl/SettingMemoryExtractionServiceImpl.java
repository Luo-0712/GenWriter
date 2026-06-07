package com.example.genwriter.service.impl;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.model.entity.TaskSession;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.message.AgentTraceEvent;
import com.example.genwriter.service.LongTermMemoryService;
import com.example.genwriter.service.SettingMemoryExtractionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettingMemoryExtractionServiceImpl implements SettingMemoryExtractionService {

    private static final Set<String> SETTING_TYPES = Set.of(
            MemoryType.WORLD_SETTING.name(),
            MemoryType.CHARACTER_PROFILE.name(),
            MemoryType.FORESHADOWING.name()
    );

    private final LongTermMemoryService memoryService;
    private final ChatClientFactory chatClientFactory;
    private final TaskSessionMapper taskSessionMapper;
    private final LongTermMemoryProperties properties;
    private final ObjectMapper objectMapper;
    private final ThoughtChainPublisher chainPublisher;

    @Override
    @Async("taskExecutor")
    public void extractAsync(String sessionId, String userInput, String finalOutput) {
        if (!properties.isEnabled() || !properties.getExtraction().isEnabled()) {
            return;
        }

        String traceSpanId = chainPublisher.publishTraceStart(sessionId, "Setting memory extraction",
                AgentTraceEvent.Kind.MEMORY, null,
                Map.of("outputLength", finalOutput != null ? finalOutput.length() : 0), null);

        int extractedCount = 0;
        int storedCount = 0;

        try {
            String projectId = resolveProjectId(sessionId);
            String scope = projectId != null && !projectId.isBlank() ? "PROJECT" : "GLOBAL";

            ChatClient extractionClient = chatClientFactory.create(properties.getExtraction().getTemperature());
            String response = extractionClient.prompt()
                    .user(buildExtractionPrompt(userInput, finalOutput))
                    .call()
                    .content();

            List<ExtractedSettingMemory> extracted = parseExtractionResult(response);
            extractedCount = extracted.size();
            int maxMemories = properties.getExtraction().getMaxMemoriesPerTurn();

            for (ExtractedSettingMemory item : extracted) {
                if (storedCount >= maxMemories) {
                    break;
                }
                if (!isValid(item)) {
                    continue;
                }

                String importance = !isBlank(item.importance()) ? item.importance() : "MEDIUM";
                try {
                    memoryService.storeMemory(buildContent(item), MemoryType.valueOf(item.memoryType()),
                            scope, projectId, sessionId, importance,
                            buildMetadata(item, scope, projectId, sessionId, importance));
                    storedCount++;
                    log.info("[SettingMemoryExtraction] stored or updated: sessionId={}, projectId={}, memoryType={}, name={}, source=setting_extraction",
                            sessionId, projectId, item.memoryType(), item.name());
                } catch (Exception e) {
                    log.warn("[SettingMemoryExtraction] store failed: sessionId={}, projectId={}, memoryType={}, name={}, error={}",
                            sessionId, projectId, item.memoryType(), item.name(), e.getMessage(), e);
                }
            }

            chainPublisher.publishTraceComplete(sessionId, traceSpanId,
                    Map.of("extracted", extractedCount, "stored", storedCount),
                    Map.of("source", "setting_extraction", "types", typeCounts(extracted)));
            log.info("[SettingMemoryExtraction] completed: sessionId={}, projectId={}, extracted={}, stored={}",
                    sessionId, projectId, extractedCount, storedCount);
        } catch (Exception e) {
            chainPublisher.publishTraceError(sessionId, traceSpanId, e.getMessage());
            log.error("[SettingMemoryExtraction] failed: sessionId={}", sessionId, e);
        }
    }

    private String resolveProjectId(String sessionId) {
        if (isBlank(sessionId)) {
            return null;
        }
        try {
            TaskSession session = taskSessionMapper.selectById(sessionId);
            return session != null ? session.getProjectId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildExtractionPrompt(String userInput, String finalOutput) {
        return """
                You are a structured memory extractor for novel writing.
                Extract only concrete story-setting facts created or confirmed in the assistant output.

                Allowed memoryType values:
                - WORLD_SETTING: locations, timeline, technology rules, factions, social rules, world constraints.
                - CHARACTER_PROFILE: character name, role, motivation, personality, background, relationship, appearance.
                - FORESHADOWING: unresolved clue, suspense, planted payoff, open plot thread.

                Rules:
                - Return pure JSON array only, no markdown.
                - Do not extract writing preferences, general domain knowledge, summaries, or commentary.
                - Each item must be atomic and reusable for future chapters.
                - Use concise names. Content should be specific and self-contained.
                - If nothing should be saved, return [].

                JSON shape:
                [{"memoryType":"WORLD_SETTING","name":"...","content":"...","importance":"HIGH|MEDIUM|LOW"}]

                User request:
                %s

                Assistant output:
                %s
                """.formatted(safe(userInput), safe(finalOutput));
    }

    private List<ExtractedSettingMemory> parseExtractionResult(String response) {
        try {
            String json = stripMarkdownCodeBlock(response);
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            return objectMapper.readerForListOf(ExtractedSettingMemory.class).readValue(root);
        } catch (Exception e) {
            log.warn("[SettingMemoryExtraction] parse failed: response={}", response, e);
            return List.of();
        }
    }

    private boolean isValid(ExtractedSettingMemory item) {
        return item != null
                && SETTING_TYPES.contains(item.memoryType())
                && !isBlank(item.name())
                && !isBlank(item.content());
    }

    private String buildContent(ExtractedSettingMemory item) {
        return "## Name\n" + item.name().trim() + "\n\n## Details\n" + item.content().trim();
    }

    private Map<String, Object> buildMetadata(ExtractedSettingMemory item,
                                              String scope,
                                              String projectId,
                                              String sessionId,
                                              String importance) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", item.name());
        metadata.put("name", item.name());
        metadata.put("type", item.memoryType());
        metadata.put("sessionId", safe(sessionId));
        metadata.put("projectId", safe(projectId));
        metadata.put("summary", item.content());
        metadata.put("keywords", List.of(item.name(), "setting"));
        metadata.put("entities", List.of(item.name()));
        metadata.put("facets", Map.of(
                "name", item.name(),
                "details", item.content()
        ));
        metadata.put("source", Map.of(
                "source", "setting_extraction",
                "service", "setting_extraction",
                "scope", scope,
                "projectId", safe(projectId),
                "sessionId", safe(sessionId),
                "importance", importance,
                "type", item.memoryType(),
                "authority", "MODEL_EXTRACTED"
        ));
        metadata.put("updatePolicy", "MERGE");
        return metadata;
    }

    private Map<String, Long> typeCounts(List<ExtractedSettingMemory> extracted) {
        if (extracted == null) {
            return Map.of();
        }
        return extracted.stream()
                .filter(this::isValid)
                .collect(Collectors.groupingBy(ExtractedSettingMemory::memoryType,
                        LinkedHashMap::new, Collectors.counting()));
    }

    private String stripMarkdownCodeBlock(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        if (cleaned.startsWith("`") && cleaned.endsWith("`")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ExtractedSettingMemory(String memoryType, String name, String content, String importance) {
    }
}
