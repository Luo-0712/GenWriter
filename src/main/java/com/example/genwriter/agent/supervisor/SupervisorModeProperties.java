package com.example.genwriter.agent.supervisor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "genwriter.supervisor")
public class SupervisorModeProperties {

    private int maxIterations = 12;
    private int maxReplanCount = 2;
    private double temperature = 0.2;
    private int maxHistoryItems = 20;
    private String fallbackWorker = "direct_answer";
    private ChapterContext chapterContext = new ChapterContext();
    private React react = new React();

    @Data
    public static class React {
        /** ReAct 模式总开关：true 走 ReactLoop 单步决策，false 走旧 generatePlan + while-loop。 */
        private boolean enabled = true;
        /** LLM 单步决策连续失败多少次后回退到旧 plan-then-execute 路径。 */
        private int maxConsecutiveFailures = 3;
    }

    @Data
    public static class ChapterContext {
        private boolean enabled = true;
        private int maxSummaries = 2;
        private int maxSummaryChars = 600;
        private double temperature = 0.2;
    }
}
