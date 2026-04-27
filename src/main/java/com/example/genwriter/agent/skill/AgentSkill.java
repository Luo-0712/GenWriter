package com.example.genwriter.agent.skill;

import java.util.Map;

/**
 * Agent Skill 接口
 * 将 Prompt 构建与 Node 执行逻辑解耦，支持配置化覆盖。
 */
public interface AgentSkill {

    /**
     * Skill 名称
     */
    String name();

    /**
     * System Prompt，定义角色、目标、步骤、约束
     */
    String systemPrompt();

    /**
     * 根据上下文构建 User Prompt
     *
     * @param context 上下文变量（userInput、outline、context 等）
     * @return 构建好的 User Prompt
     */
    String buildUserPrompt(Map<String, Object> context);

    /**
     * 输出格式说明，用于日志或调试
     */
    String outputFormatDescription();
}
