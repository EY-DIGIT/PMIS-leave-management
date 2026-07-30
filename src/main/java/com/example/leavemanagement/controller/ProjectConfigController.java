package com.example.leavemanagement.controller;

import com.example.leavemanagement.entity.ProjectConfig;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.ProjectConfigRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/project-config")
@Tag(name = "Project Config", description = "Per-project quarter cycle day configuration")
public class ProjectConfigController {

    private final ProjectConfigRepository repository;

    public ProjectConfigController(ProjectConfigRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "Get quarter cycle day for a project/organisation")
    public ResponseEntity<Map<String, Object>> get(
            @RequestParam String projectId,
            @RequestParam String organisationId) {
        ProjectConfig cfg = repository.findByProjectIdAndOrganisationId(projectId, organisationId)
                .orElseThrow(() -> new NotFoundException(
                        "No config found for project " + projectId + " / org " + organisationId));
        return ResponseEntity.ok(toMap(cfg));
    }

    @PutMapping
    @Operation(summary = "Create or update quarter cycle day (1–28) for a project/organisation")
    public ResponseEntity<Map<String, Object>> put(
            @RequestParam String projectId,
            @RequestParam String organisationId,
            @RequestBody Map<String, Integer> body) {
        Integer day = body.get("quarterCycleDay");
        if (day == null || day < 1 || day > 28) {
            throw new BadRequestException("quarterCycleDay must be between 1 and 28");
        }
        ProjectConfig cfg = repository
                .findByProjectIdAndOrganisationId(projectId, organisationId)
                .orElseGet(() -> new ProjectConfig(projectId, organisationId, day));
        cfg.setQuarterCycleDay(day);
        repository.save(cfg);
        return ResponseEntity.ok(toMap(cfg));
    }

    private Map<String, Object> toMap(ProjectConfig c) {
        return Map.of(
                "id",              c.getId(),
                "projectId",       c.getProjectId(),
                "organisationId",  c.getOrganisationId(),
                "quarterCycleDay", c.getQuarterCycleDay());
    }
}
