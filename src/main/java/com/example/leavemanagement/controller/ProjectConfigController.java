package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.ProjectConfigRequest;
import com.example.leavemanagement.dto.ProjectConfigResponse;
import com.example.leavemanagement.dto.ResourceProjectRequest;
import com.example.leavemanagement.dto.ResourceProjectResponse;
import com.example.leavemanagement.service.ProjectConfigService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectConfigController {

    private final ProjectConfigService projectConfigService;

    public ProjectConfigController(ProjectConfigService projectConfigService) {
        this.projectConfigService = projectConfigService;
    }

    @PostMapping
    public ResponseEntity<ProjectConfigResponse> createOrUpdate(@Valid @RequestBody ProjectConfigRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectConfigService.createOrUpdate(req));
    }

    @GetMapping
    public List<ProjectConfigResponse> listConfigs() {
        return projectConfigService.listConfigs();
    }

    @GetMapping("/{projectId}")
    public ProjectConfigResponse getConfig(@PathVariable String projectId) {
        return projectConfigService.getConfig(projectId);
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteConfig(@PathVariable String projectId) {
        projectConfigService.deleteConfig(projectId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{projectId}/resources")
    public ResponseEntity<ResourceProjectResponse> assignResource(
            @PathVariable String projectId,
            @Valid @RequestBody ResourceProjectRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectConfigService.assignResource(projectId, req));
    }

    @GetMapping("/{projectId}/resources")
    public List<ResourceProjectResponse> listResources(@PathVariable String projectId) {
        return projectConfigService.listResources(projectId);
    }

    @DeleteMapping("/resources/{attendanceId}")
    public ResponseEntity<Void> removeResource(@PathVariable String attendanceId) {
        projectConfigService.removeResource(attendanceId);
        return ResponseEntity.noContent().build();
    }
}
