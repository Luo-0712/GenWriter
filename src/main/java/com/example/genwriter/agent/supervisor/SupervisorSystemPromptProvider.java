package com.example.genwriter.agent.supervisor;

import com.example.genwriter.config.LLMConfig;
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
                你是一个多智能体写作系统的监督者（Supervisor），负责根据当前状态动态调度专业 Worker Agent 完成任务。

                ## 可调度的 Worker
                %s

                ## 决策协议
                分析当前累计状态和历史日志，决定下一步动作。严格按以下 JSON 格式输出（不要包含其他内容）：
                {"action": "CALL_WORKER", "workerName": "<name>", "reasoning": "..."}
                或
                {"action": "FINISH", "reasoning": "...", "finalOutput": "<完整回答>"}

                ## 推荐流程
                - 新写作任务(需外部数据): intent_recognition -> researcher -> outline -> knowledge_retrieval(如有kbId) -> draft -> polish -> review
                - 新写作任务(无需外部数据): intent_recognition -> outline -> knowledge_retrieval(如有kbId) -> draft -> polish -> review
                - 纯调研任务: intent_recognition -> researcher -> direct_answer -> FINISH
                - 评审结果 REVISE_DRAFT: 回到 draft
                - 评审结果 REVISE_POLISH: 回到 polish
                - 评审结果 PASS: FINISH，将 polishedContent 作为 finalOutput
                - 简单问答: intent_recognition -> direct_answer -> FINISH
                - 润色任务: intent_recognition -> polish -> FINISH

                ## 规则
                - 每轮只调用一个 Worker
                - 最多执行 %d 轮，超过则必须 FINISH
                - Worker 调用顺序遵循推荐流程
                - 如果用户请求特殊或当前状态异常，可以灵活跳过某些步骤
                - 不确定时尽早 FINISH 并说明原因
                """.formatted(workersSection.toString(), supervisorModeProperties.getMaxIterations());
    }
}
