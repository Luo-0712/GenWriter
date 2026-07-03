package com.example.genwriter.service;

import com.example.genwriter.model.dto.request.DocumentEditSuggestionRequest;
import com.example.genwriter.model.dto.response.DocumentEditSuggestionResponse;

public interface DocumentEditSuggestionService {

    DocumentEditSuggestionResponse suggestEdit(String documentId, DocumentEditSuggestionRequest request);
}
