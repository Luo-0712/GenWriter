package com.example.genwriter.controller;

import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.request.UpdateWritingOutputSettingsRequest;
import com.example.genwriter.model.dto.response.WritingOutputSettings;
import com.example.genwriter.service.WritingOutputSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final WritingOutputSettingsService writingOutputSettingsService;

    @GetMapping("/writing-output")
    public ApiResponse<WritingOutputSettings> getWritingOutputSettings() {
        return ApiResponse.success(writingOutputSettingsService.getSettings());
    }

    @PutMapping("/writing-output")
    public ApiResponse<WritingOutputSettings> updateWritingOutputSettings(
            @RequestBody UpdateWritingOutputSettingsRequest request) {
        if (request == null || (request.getMarkdownEnabled() == null
                && request.getParallelChapterWritingEnabled() == null)) {
            return ApiResponse.error("400", "至少提供一个可更新字段");
        }
        return ApiResponse.success(
                writingOutputSettingsService.updateSettings(
                        request.getMarkdownEnabled(),
                        request.getParallelChapterWritingEnabled()));
    }
}
