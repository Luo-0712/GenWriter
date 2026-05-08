package com.example.genwriter.service;

import com.example.genwriter.model.dto.response.LearningResultVO;

public interface WritingSkillLearningService {

    LearningResultVO analyzeAndStore(String articleContent, String description,
                                      String scope, String projectId, String sessionId);
}
