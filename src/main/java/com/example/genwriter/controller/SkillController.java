package com.example.genwriter.controller;

import com.example.genwriter.agent.skill.SkillService;
import com.example.genwriter.model.common.ApiResponse;
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
