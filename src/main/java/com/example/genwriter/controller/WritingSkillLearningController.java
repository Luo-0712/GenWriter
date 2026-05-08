package com.example.genwriter.controller;

import com.example.genwriter.model.dto.response.LearningResultVO;
import com.example.genwriter.service.WritingSkillLearningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/writing-skills")
@RequiredArgsConstructor
public class WritingSkillLearningController {

    private final WritingSkillLearningService learningService;

    @PostMapping("/learn")
    public ResponseEntity<LearningResultVO> learnFromArticle(@Valid @RequestBody LearnRequest request) {
        log.info("收到文章学习请求: contentLength={}, scope={}",
                request.getArticleContent() != null ? request.getArticleContent().length() : 0,
                request.getScope());

        LearningResultVO result = learningService.analyzeAndStore(
                request.getArticleContent(),
                request.getDescription(),
                request.getScope(),
                request.getProjectId(),
                request.getSessionId()
        );

        return ResponseEntity.ok(result);
    }

    @Data
    @Builder
    public static class LearnRequest {
        @NotBlank(message = "文章内容不能为空")
        private String articleContent;

        private String description;
        private String scope;
        private String projectId;
        private String sessionId;
    }
}
