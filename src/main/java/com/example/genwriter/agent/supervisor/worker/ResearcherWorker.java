package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.skill.ResearcherSkill;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.agent.tool.WebSearchResult;
import com.example.genwriter.agent.tool.WebSearchTool;
import com.example.genwriter.config.ResearcherProperties;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 研究调研 Worker
 * 执行多步骤网络调研：制定搜索计划 → 执行搜索 → 综合报告 → 验证完整性
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResearcherWorker implements WorkerAgent {

    private static final double PLAN_TEMPERATURE = 0.3;
    private static final double SYNTHESIZE_TEMPERATURE = 0.5;
    private static final double VERIFY_TEMPERATURE = 0.2;

    private final ChatClientFactory chatClientFactory;
    private final ResearcherSkill skill;
    private final WebSearchTool webSearchTool;
    private final WorkerRegistry registry;
    private final SseService sseService;
    private final ObjectMapper objectMapper;
    private final ResearcherProperties properties;

    private ChatClient planChatClient;
    private ChatClient synthesizeChatClient;
    private ChatClient verifyChatClient;

    @PostConstruct
    void init() {
        this.planChatClient = chatClientFactory.create(PLAN_TEMPERATURE);
        this.synthesizeChatClient = chatClientFactory.create(SYNTHESIZE_TEMPERATURE);
        this.verifyChatClient = chatClientFactory.create(VERIFY_TEMPERATURE);
        registry.register(this);
    }

    @Override
    public String name() {
        return "researcher";
    }

    @Override
    public String description() {
        return "执行多步骤网络调研：制定搜索计划→执行搜索→综合报告→验证完整性";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> state) throws Exception {
        String sessionId = (String) state.getOrDefault("sessionId", "");
        String userInput = (String) state.getOrDefault("userInput", "");
        String existingContext = (String) state.getOrDefault("context", "");

        String researchReport = null;
        List<WebSearchResult> allResults = new ArrayList<>();
        StringBuilder accumulatedContext = new StringBuilder(existingContext);

        int loopCount = 0;
        boolean isComplete = false;

        while (!isComplete && loopCount <= properties.getMaxVerificationLoops()) {
            loopCount++;
            log.info("[ResearcherWorker] 调研轮次 {}/{}: sessionId={}", loopCount, properties.getMaxVerificationLoops() + 1, sessionId);

            // Phase 1: Plan
            publishStatus(sessionId, SseMessage.Type.AI_PLANNING, "【调研】正在制定搜索计划...");
            ResearchPlan plan = generatePlan(sessionId, userInput, accumulatedContext.toString());
            log.info("[ResearcherWorker] 搜索计划: queries={}, reasoning={}", plan.queries().size(), plan.reasoning());
            publishStatus(sessionId, SseMessage.Type.AI_PLANNING,
                    "【调研】搜索计划: " + String.join(", ", plan.queries()));

            // Phase 2: Search
            List<WebSearchResult> roundResults = executeSearches(sessionId, plan.queries());
            allResults.addAll(roundResults);
            log.info("[ResearcherWorker] 搜索结果: count={}", roundResults.size());

            // Phase 3: Synthesize
            publishStatus(sessionId, SseMessage.Type.AI_THINKING, "【调研】正在综合研究报告...");
            ResearchSynthesis synthesis = synthesize(sessionId, userInput, allResults);
            researchReport = synthesis.researchReport();
            log.info("[ResearcherWorker] 综合完成: reportLength={}, sources={}, findings={}",
                    researchReport.length(), synthesis.researchSources().size(), synthesis.keyFindings().size());
            publishStatus(sessionId, SseMessage.Type.AI_THINKING,
                    "【调研】报告综合完成，长度=" + researchReport.length() + " 字符");

            // Phase 4: Verify
            if (loopCount <= properties.getMaxVerificationLoops()) {
                publishStatus(sessionId, SseMessage.Type.AI_THINKING, "【调研】正在验证报告完整性...");
                VerificationResult verification = verify(sessionId, userInput, researchReport);
                isComplete = verification.isComplete();
                log.info("[ResearcherWorker] 验证结果: isComplete={}, gaps={}", isComplete, verification.gaps());

                if (!isComplete) {
                    accumulatedContext.append("\n\n【信息缺口 - 需要补充搜索】\n").append(verification.gaps());
                    publishStatus(sessionId, SseMessage.Type.AI_THINKING,
                            "【调研】发现信息缺口，补充搜索: " + verification.gaps());
                }
            } else {
                isComplete = true;
            }
        }

        // Deduplicate results by URL
        Map<String, WebSearchResult> uniqueResults = new LinkedHashMap<>();
        for (WebSearchResult r : allResults) {
            uniqueResults.putIfAbsent(r.url(), r);
        }
        List<WebSearchResult> dedupedResults = new ArrayList<>(uniqueResults.values());

        // Build final sources list
        List<Map<String, String>> sources = dedupedResults.stream()
                .map(r -> Map.of("title", r.title(), "url", r.url()))
                .toList();

        // Append research to context
        if (!researchReport.isBlank()) {
            accumulatedContext.append("\n\n【网络调研结果】\n").append(researchReport);
        }

        log.info("[ResearcherWorker] 调研完成: totalSources={}, reportLength={}", sources.size(), researchReport.length());
        publishStatus(sessionId, SseMessage.Type.AI_EXECUTING, "【调研】调研完成，共 " + sources.size() + " 个来源");

        return Map.of(
                "researchReport", researchReport,
                "researchSources", objectMapper.writeValueAsString(sources),
                "keyFindings", extractKeyFindings(researchReport),
                "context", accumulatedContext.toString(),
                "currentNode", "ResearcherWorker"
        );
    }

    // -------------------------------------------------------------------------
    // Phase 1: Plan
    // -------------------------------------------------------------------------

    private ResearchPlan generatePlan(String sessionId, String userInput, String context) {
        String prompt = skill.buildPlanningPrompt(userInput, context);
        String response = planChatClient.prompt()
                .system(skill.systemPrompt())
                .user(prompt)
                .call()
                .content();

        try {
            String json = stripMarkdownCodeBlock(response);
            JsonNode root = objectMapper.readTree(json);
            List<String> queries = new ArrayList<>();
            JsonNode queriesNode = root.path("queries");
            if (queriesNode.isArray()) {
                for (JsonNode q : queriesNode) {
                    if (q.isTextual()) queries.add(q.asText());
                }
            }
            String reasoning = root.path("reasoning").asText("");

            // Limit queries
            if (queries.size() > properties.getMaxSearchQueries()) {
                queries = queries.subList(0, properties.getMaxSearchQueries());
            }
            if (queries.isEmpty()) {
                queries = List.of(userInput); // fallback
            }
            return new ResearchPlan(queries, reasoning);
        } catch (Exception e) {
            log.warn("[ResearcherWorker] 计划JSON解析失败，兜底: response={}", response, e);
            return new ResearchPlan(List.of(userInput), "解析失败，使用原始请求作为搜索查询");
        }
    }

    // -------------------------------------------------------------------------
    // Phase 2: Search
    // -------------------------------------------------------------------------

    private List<WebSearchResult> executeSearches(String sessionId, List<String> queries) {
        List<WebSearchResult> all = new ArrayList<>();
        int idx = 0;
        for (String query : queries) {
            idx++;
            publishStatus(sessionId, SseMessage.Type.AI_EXECUTING,
                    "【调研】正在搜索 (" + idx + "/" + queries.size() + "): " + query);
            try {
                List<WebSearchResult> results = webSearchTool.search(query, properties.getMaxSearchResultsPerQuery());
                all.addAll(results);
            } catch (Exception e) {
                log.error("[ResearcherWorker] 搜索失败: query={}", query, e);
            }
        }
        return all;
    }

    // -------------------------------------------------------------------------
    // Phase 3: Synthesize
    // -------------------------------------------------------------------------

    private ResearchSynthesis synthesize(String sessionId, String userInput, List<WebSearchResult> results) {
        String prompt = skill.buildSynthesisPrompt(userInput, results);
        String response = synthesizeChatClient.prompt()
                .system(skill.systemPrompt())
                .user(prompt)
                .call()
                .content();

        try {
            String json = stripMarkdownCodeBlock(response);
            JsonNode root = objectMapper.readTree(json);
            String report = root.path("researchReport").asText("");

            List<Map<String, String>> sources = new ArrayList<>();
            JsonNode sourcesNode = root.path("researchSources");
            if (sourcesNode.isArray()) {
                for (JsonNode s : sourcesNode) {
                    sources.add(Map.of(
                            "title", s.path("title").asText(""),
                            "url", s.path("url").asText("")
                    ));
                }
            }

            List<String> findings = new ArrayList<>();
            JsonNode findingsNode = root.path("keyFindings");
            if (findingsNode.isArray()) {
                for (JsonNode f : findingsNode) {
                    if (f.isTextual()) findings.add(f.asText());
                }
            }

            return new ResearchSynthesis(report, sources, findings);
        } catch (Exception e) {
            log.warn("[ResearcherWorker] 综合JSON解析失败，使用原始响应: response={}", response, e);
            return new ResearchSynthesis(response, List.of(), List.of());
        }
    }

    // -------------------------------------------------------------------------
    // Phase 4: Verify
    // -------------------------------------------------------------------------

    private VerificationResult verify(String sessionId, String userInput, String researchReport) {
        String prompt = skill.buildVerificationPrompt(userInput, researchReport);
        String response = verifyChatClient.prompt()
                .system(skill.systemPrompt())
                .user(prompt)
                .call()
                .content();

        try {
            String json = stripMarkdownCodeBlock(response);
            JsonNode root = objectMapper.readTree(json);
            boolean isComplete = root.path("isComplete").asBoolean(true);
            String gaps = root.path("gaps").asText("");
            String reasoning = root.path("reasoning").asText("");
            return new VerificationResult(isComplete, gaps, reasoning);
        } catch (Exception e) {
            log.warn("[ResearcherWorker] 验证JSON解析失败，兜底通过: response={}", response, e);
            return new VerificationResult(true, "", "解析失败，默认通过");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String stripMarkdownCodeBlock(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        if (cleaned.startsWith("`") && cleaned.endsWith("`")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private void publishStatus(String sessionId, SseMessage.Type type, String statusText) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(type)
                    .payload(SseMessage.Payload.builder()
                            .statusText(statusText)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE 状态推送失败: {}", e.getMessage());
        }
    }

    private String extractKeyFindings(String researchReport) {
        // 简单提取，如果报告太长取前500字作为关键发现
        if (researchReport == null || researchReport.isBlank()) return "[]";
        String summary = researchReport.length() > 500 ? researchReport.substring(0, 500) + "..." : researchReport;
        try {
            return objectMapper.writeValueAsString(List.of(summary));
        } catch (Exception e) {
            return "[]";
        }
    }

    // -------------------------------------------------------------------------
    // Internal DTOs
    // -------------------------------------------------------------------------

    private record ResearchPlan(List<String> queries, String reasoning) {
    }

    private record ResearchSynthesis(String researchReport, List<Map<String, String>> researchSources, List<String> keyFindings) {
    }

    private record VerificationResult(boolean isComplete, String gaps, String reasoning) {
    }
}
