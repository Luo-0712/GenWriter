package com.example.genwriter.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LearningResultVO {

    private boolean success;
    private String message;
    private int extractedCount;
    private int storedCount;
    private List<SkillSummary> skills;

    @Data
    @Builder
    public static class SkillSummary {
        private String skillName;
        private String category;

        public SkillSummary(String skillName, String category) {
            this.skillName = skillName;
            this.category = category;
        }
    }
}
