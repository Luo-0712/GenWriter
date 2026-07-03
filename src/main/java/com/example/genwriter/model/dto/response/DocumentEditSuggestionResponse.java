package com.example.genwriter.model.dto.response;

import com.example.genwriter.model.enums.DocumentEditSuggestionMode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DocumentEditSuggestionResponse {

    private DocumentEditSuggestionMode mode;
    private String replacementMarkdown;
    private String selectionFingerprint;
    private LocalDateTime createdAt;
}
