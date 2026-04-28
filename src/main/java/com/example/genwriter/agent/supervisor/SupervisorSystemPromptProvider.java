package com.example.genwriter.agent.supervisor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class SupervisorSystemPromptProvider {

    private final WorkerRegistry workerRegistry;
    private final SupervisorModeProperties supervisorModeProperties;

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

        return """
                ## 角色
                你是一个多智能体写作系统的监督者（Supervisor），负责根据用户需求制定完整的执行计划。

                ## 可调度的 Worker
                %s

                ## 决策协议
                分析用户需求和当前状态，制定执行计划或决定结束。严格按以下 JSON 格式输出（不要包含其他内容）：

                制定计划：
                {"action": "PLAN", "steps": ["worker1", "worker2", ...], "reasoning": "为什么这样规划"}

                任务完成：
                {"action": "FINISH", "reasoning": "为什么结束", "finalOutput": "<完整回答>"}

                ## 推荐流程
                - 新写作任务(需外部数据): intent_recognition -> researcher -> outline -> draft -> polish -> review
                - 新写作任务(无需外部数据): intent_recognition -> outline -> draft -> polish -> review
                - 纯调研任务: intent_recognition -> researcher -> direct_answer
                - 评审结果 REVISE_DRAFT: 回到 draft
                - 评审结果 REVISE_POLISH: 回到 polish
                - 评审结果 PASS: FINISH，将 polishedContent 作为 finalOutput
                - 简单问答: intent_recognition -> direct_answer
                - 润色任务: intent_recognition -> polish
                - 知识库问答(有kbId): intent_recognition -> direct_answer（DirectAnswerWorker 内部自主调用知识库搜索）

                ## 规则
                - 一次性制定完整计划，不要逐步决策
                - 步骤顺序遵循推荐流程
                - 如果用户请求特殊或当前状态异常，可以灵活调整步骤
                - 最多执行 %d 个步骤
                - 不确定时尽早 FINISH 并说明原因
                """.formatted(workersSection.toString(), supervisorModeProperties.getMaxIterations());
    }
}
