package com.example.genwriter.model.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class SkillCreateRequest {
    private String name;
    private String displayName;
    private String description;
    private String category;
    private List<String> tags;
    private String content;
    private Boolean disableModelInvocation;
    private Boolean userInvocable;
    private List<String> allowedTools;
    private String argumentHint;
}
