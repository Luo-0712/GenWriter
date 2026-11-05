package com.example.genwriter.service;

public interface MemoryExtractionService {

    void extractAsync(String sessionId,
                      String userInput, String finalOutput);

    void extractForProjectAsync(String projectId, String sessionId, String userInput, String finalOutput);
}
