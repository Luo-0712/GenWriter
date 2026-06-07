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

    @Data
    public static class ChapterContext {
        private boolean enabled = true;
        private int maxSummaries = 2;
        private int maxSummaryChars = 600;
        private double temperature = 0.2;
    }
}
