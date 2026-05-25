package com.example.genwriter.agent.skill;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SkillResource {

    private String name;
    private String displayName;
    private String description;
    private String category;
    private List<String> tags;
    private String version;
    private String content;
    private String contentPreview;
    private String sourceFile;
    private boolean builtIn;
}
