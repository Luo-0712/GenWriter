package com.example.genwriter.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SkillVO {

    private String name;
    private String displayName;
    private String description;
    private String category;
    private List<String> tags;
    private String version;
    private String content;
    private String contentPreview;
    private boolean enabled;
    private boolean builtIn;
    private String sourceFile;
}
