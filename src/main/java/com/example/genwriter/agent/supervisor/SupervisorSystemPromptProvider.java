package com.example.genwriter.agent.supervisor;

import com.example.genwriter.agent.skill.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SupervisorSystemPromptProvider {

    private final WorkerRegistry workerRegistry;
    private final SupervisorModeProperties supervisorModeProperties;
    private final SkillService skillService;

    public String buildSystemPrompt() {
        Map<String, String> workerDescriptions = workerRegistry.getWorkerDescriptions();

        StringBuilder workersSection = new StringBuilder();
        for (var entry : workerDescriptions.entrySet()) {
            workersSection.append("- **")
                    .append(entry.getKey())
                    .append("**: ")
                    .append(entry.getValue())
                    .append("\n");
        }

        String skillsSummary = skillService.getEnabledSkillsSummary();
        String skillsSection = skillsSummary.isBlank() ? "" : """
                ## 可用 Skill（工作流与经验）
                以下是已启用的 Skill 摘要。当任务匹配某个 Skill 时，使用 read_skill_detail 工具读取完整内容后再规划。
                不匹配的 Skill 无需读取，避免浪费 Token。

                %s
                """.formatted(skillsSummary);

        return """
                ## 角色
                你是一个多智能体写作系统的监督者（Supervisor），负责根据用户需求制定完整的执行计划。

                ## 可调度的 Worker
                %s
                %s
                ## 决策协议
                分析用户需求和当前状态，制定执行计划或决定结束。严格按以下 JSON 格式输出（不要包含其他内容）：

                制定计划：
                {"action": "PLAN", "steps": ["worker1", "worker2", ...], "reasoning": "为什么这样规划"}

                任务完成：
                {"action": "FINISH", "reasoning": "为什么结束", "finalOutput": "<完整回答>"}

                ## 推荐流程
                - 新写作任务(需外部数据): intent_recognition -> researcher -> outline -> draft -> polish -> review
                - 新写作任务(有kbId): intent_recognition -> researcher -> outline -> draft -> polish -> review（researcher 会优先检索知识库，再补充网络搜索）
                - 新写作任务(无需外部数据): intent_recognition -> outline -> draft -> polish -> review
                - 纯调研任务: intent_recognition -> researcher -> direct_answer
                - 评审结果 REVISE_DRAFT: 回到 draft
                - 评审结果 REVISE_POLISH: 回到 polish
                - 评审结果 PASS: FINISH，将 polishedContent 作为 finalOutput
                - 简单问答: intent_recognition -> direct_answer
                - 润色任务: intent_recognition -> polish
                - 知识库问答(有kbId，非写作): intent_recognition -> direct_answer（DirectAnswerWorker 内部自主调用知识库搜索）

                ## 规则
                - 一次性制定完整计划，不要逐步决策
                - 步骤顺序遵循推荐流程
                - 如果用户请求特殊或当前状态异常，可以灵活调整步骤
                - 最多执行 %d 个步骤
                - 不确定时尽早 FINISH 并说明原因
                """.formatted(workersSection.toString(), skillsSection, supervisorModeProperties.getMaxIterations());
    }

    /**
     * ReAct 模式系统提示：单步决策协议。
     * 配合 {@link #buildReactStepPrompt} 使用，由 LLM 每步输出单个 action。
     */
    public String buildReactSystemPrompt() {
        Map<String, String> workerDescriptions = workerRegistry.getWorkerDescriptions();

        StringBuilder workersSection = new StringBuilder();
        for (var entry : workerDescriptions.entrySet()) {
            workersSection.append("- **")
                    .append(entry.getKey())
                    .append("**: ")
                    .append(entry.getValue())
                    .append("\n");
        }

        String skillsSummary = skillService.getEnabledSkillsSummary();
        String skillsSection = skillsSummary.isBlank() ? "" : """
                ## 可用 Skill（工作流与经验）
                当任务匹配某个 Skill 时，可调用 read_skill_detail 工具读取完整内容后再决策下一步。
                不匹配的 Skill 无需读取，避免浪费 Token。

                %s
                """.formatted(skillsSummary);

        return """
                ## 角色
                你是一个多智能体写作系统的监督者（Supervisor），采用 ReAct 模式逐步决策：
                每一步观察上一步产物 → 思考 → 选择下一个 action，而非一次性规划全流程。

                ## 可调度的 Worker
                %s
                %s
                ## 决策协议（单步）
                每次只输出「下一个 action」或「finish」，严格按以下 JSON 格式输出（不要包含其他内容）：

                选择下一个 worker：
                {"thought": "对上一步产物的观察与思考", "action": "<workerName>", "reasoning": "为什么选这一步"}

                任务完成：
                {"thought": "当前产物已满足用户需求", "action": "finish", "reasoning": "为什么结束"}

                ## 推荐流程（软引导，可按状态灵活调整）
                - 新写作任务(需外部数据): intent_recognition -> researcher -> outline -> draft -> polish -> review -> finish
                - 新写作任务(有kbId): intent_recognition -> researcher -> outline -> draft -> polish -> review -> finish
                - 新写作任务(无需外部数据): intent_recognition -> outline -> draft -> polish -> review -> finish
                - 纯调研任务: intent_recognition -> researcher -> direct_answer -> finish
                - 简单问答: intent_recognition -> direct_answer -> finish
                - 润色任务: intent_recognition -> polish -> review -> finish
                - 知识库问答(有kbId，非写作): intent_recognition -> direct_answer -> finish
                - 评审结果 REVISE_DRAFT: 下一步回到 draft
                - 评审结果 REVISE_POLISH: 下一步回到 polish
                - 评审结果 PASS: 下一步 finish

                ## 规则
                - 每次只决策一步，下一步依据上一步的真实产物（observation）来选
                - 首步通常先 intent_recognition（识别意图与 writingType），除非用户输入已是明确的知识问答
                - 不要重复已完成的步骤（如 outline 已生成就不要再 outline）
                - 评审 PASS 或产物已满足需求时，立即 finish
                - 最多执行 %d 个步骤，达到上限会强制结束，避免无意义的循环
                - 不确定时尽早 finish 并说明原因
                """.formatted(workersSection.toString(), skillsSection, supervisorModeProperties.getMaxIterations());
    }

    /**
     * ReAct 单步决策的用户 prompt：当前 state 摘要 + 已执行 step 历史 + 可用 worker 列表。
     *
     * @param state   当前累计 state（含 userInput、各 worker 产物）
     * @param history 已执行步骤的 observation 摘要列表（每个元素是 buildWorkerOutputSummary 的产物）
     */
    public String buildReactStepPrompt(Map<String, Object> state, List<Object> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前状态\n");
        sb.append("- userInput: ").append(truncate(asString(state.getOrDefault("userInput", "")), 500)).append("\n");
        sb.append("- kbId: ").append(state.getOrDefault("kbId", "")).append("\n");
        sb.append("- writingGenre: ").append(state.getOrDefault("writingGenre", "UNKNOWN"))
                .append(" (").append(state.getOrDefault("genreLabel", "通用写作")).append(")\n");
        sb.append("- outputFormat: ").append(state.getOrDefault("outputFormat", "markdown")).append("\n");

        String writingType = (String) state.getOrDefault("writingType", "AUTO");
        sb.append("- writingType: ").append(writingType).append("\n");

        appendStateSummary(sb, state);

        // 用户显式指定模式，作为硬约束引导
        if (!"AUTO".equals(writingType)) {
            String constraint = switch (writingType) {
                case "CREATE" -> "用户指定「新建文档」模式，流程应覆盖 outline → draft → polish → review。";
                case "CONTINUE" -> "用户指定「续写」模式，流程应基于已有 draft 续写 → polish → review。";
                case "POLISH" -> "用户指定「润色优化」模式，流程应为 polish → review。";
                case "KNOWLEDGE_QA" -> "用户指定「知识问答」模式，流程应为 direct_answer。";
                default -> "";
            };
            if (!constraint.isEmpty()) {
                sb.append("\n## 模式约束\n").append(constraint).append("\n");
            }
        }

        sb.append("\n## 已执行步骤（observation）\n");
        if (history == null || history.isEmpty()) {
            sb.append("(尚无已执行步骤，这是第一步)\n");
        } else {
            for (int i = 0; i < history.size(); i++) {
                sb.append(i + 1).append(". ").append(history.get(i)).append("\n");
            }
        }

        sb.append("\n请决策下一个 action（输出单步 JSON）。");
        return sb.toString();
    }

    private void appendStateSummary(StringBuilder sb, Map<String, Object> state) {
        if (state.containsKey("intent")) {
            sb.append("- intent: ").append(state.get("intent")).append("\n");
        }
        if (state.containsKey("outline")) {
            String outline = (String) state.get("outline");
            sb.append("- outline: ").append(outline != null ? outline.length() + " chars" : "null").append("\n");
        }
        if (state.containsKey("draft")) {
            String draft = (String) state.get("draft");
            sb.append("- draft: ").append(draft != null ? draft.length() + " chars" : "null").append("\n");
        }
        if (state.containsKey("selectedDocumentContent")) {
            String content = (String) state.get("selectedDocumentContent");
            sb.append("- selectedDocument: ")
                    .append(state.getOrDefault("selectedDocumentTitle", "未命名文稿"))
                    .append(" V")
                    .append(state.getOrDefault("selectedDocumentVersion", "?"))
                    .append(", ")
                    .append(content != null ? content.length() + " chars" : "null")
                    .append("\n");
        }
        if (state.containsKey("polishedContent")) {
            String pc = (String) state.get("polishedContent");
            sb.append("- polishedContent: ").append(pc != null ? pc.length() + " chars" : "null").append("\n");
        }
        if (state.containsKey("reviewResult")) {
            sb.append("- reviewResult: ").append(state.get("reviewResult")).append("\n");
        }
        if (state.containsKey("reviewFeedback")) {
            sb.append("- reviewFeedback: ").append(truncate(asString(state.getOrDefault("reviewFeedback", "")), 200)).append("\n");
        }
        if (state.containsKey("researchReport")) {
            String report = (String) state.get("researchReport");
            sb.append("- researchReport: ").append(report != null ? report.length() + " chars" : "null").append("\n");
        }
        if (state.containsKey("context")) {
            String ctx = (String) state.get("context");
            sb.append("- context: ").append(ctx != null && !ctx.isBlank() ? ctx.length() + " chars" : "empty").append("\n");
        }
    }

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
