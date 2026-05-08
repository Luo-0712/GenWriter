package com.example.genwriter.service;

public interface WritingSkillExtractionService {

    void extractAsync(String sessionId, String userInput, String assistantOutput, String writingType);
}
