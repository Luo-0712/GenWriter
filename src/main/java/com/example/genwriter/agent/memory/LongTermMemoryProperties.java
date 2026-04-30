package com.example.genwriter.agent.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "genwriter.long-term-memory")
public class LongTermMemoryProperties {

    private boolean enabled = true;

    private Retrieval retrieval = new Retrieval();

    private Extraction extraction = new Extraction();

    @Data
    public static class Retrieval {
        private int maxResults = 5;
        private double similarityThreshold = 0.65;
        private double dedupThreshold = 0.85;
    }

    @Data
    public static class Extraction {
        private boolean enabled = true;
        private boolean async = true;
        private int maxMemoriesPerTurn = 5;
        private double temperature = 0.1;
    }
}
