package com.example.genwriter.controller;

import com.example.genwriter.agent.skill.SkillService;
import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.request.SkillCreateRequest;
import com.example.genwriter.model.dto.request.SkillUpdateRequest;
import com.example.genwriter.model.vo.SkillVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    @GetMapping
    public ApiResponse<List<SkillVO>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag) {
        return ApiResponse.success(skillService.listSkills(category, tag));
    }

    @GetMapping("/{name}")
    public ApiResponse<SkillVO> get(@PathVariable String name) {
        SkillVO skill = skillService.getSkill(name);
        if (skill == null) {
            return ApiResponse.error("404", "未找到 skill: " + name);
        }
        return ApiResponse.success(skill);
    }

    @PostMapping
    public ApiResponse<SkillVO> create(@RequestBody SkillCreateRequest request) {
        try {
            SkillVO skill = skillService.createSkill(
                    request.getName(), request.getDisplayName(), request.getDescription(),
                    request.getCategory(), request.getTags(), request.getContent(),
                    request.getDisableModelInvocation(), request.getUserInvocable(),
                    request.getAllowedTools(), request.getArgumentHint());
            return ApiResponse.success(skill);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("400", e.getMessage());
        }
    }

    @PutMapping("/{name}")
    public ApiResponse<SkillVO> update(@PathVariable String name, @RequestBody SkillUpdateRequest request) {
        try {
            SkillVO skill = skillService.updateSkill(
                    name, request.getDisplayName(), request.getDescription(),
                    request.getCategory(), request.getTags(), request.getContent(), request.getEnabled(),
                    request.getDisableModelInvocation(), request.getUserInvocable(),
                    request.getAllowedTools(), request.getArgumentHint());
            return ApiResponse.success(skill);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("400", e.getMessage());
        }
    }

    @DeleteMapping("/{name}")
    public ApiResponse<Void> delete(@PathVariable String name) {
        try {
            skillService.deleteSkill(name);
            return ApiResponse.success(null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("404", e.getMessage());
        }
    }

    @PostMapping("/{name}/toggle")
    public ApiResponse<Void> toggle(@PathVariable String name,
                                    @RequestParam boolean enabled) {
        skillService.setEnabled(name, enabled);
        return ApiResponse.success(null);
    }

    @PostMapping("/reload")
    public ApiResponse<Map<String, Object>> reload() {
        Map<String, Object> result = skillService.reload();
        return ApiResponse.success(result);
    }

    @GetMapping("/categories")
    public ApiResponse<List<String>> categories() {
        return ApiResponse.success(skillService.getCategories());
    }
}
