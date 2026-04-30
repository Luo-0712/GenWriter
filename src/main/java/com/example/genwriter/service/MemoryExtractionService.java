package com.example.genwriter.service;

public interface MemoryExtractionService {

    void extractAsync(String sessionId, String documentId,
                      String userInput, String finalOutput);
}
