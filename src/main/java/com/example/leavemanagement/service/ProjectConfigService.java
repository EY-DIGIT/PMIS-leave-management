package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.ProjectConfigRequest;
import com.example.leavemanagement.dto.ProjectConfigResponse;
import com.example.leavemanagement.dto.ResourceProjectRequest;
import com.example.leavemanagement.dto.ResourceProjectResponse;
import com.example.leavemanagement.entity.ProjectConfig;
import com.example.leavemanagement.entity.ResourceProjectMapping;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.ProjectConfigRepository;
import com.example.leavemanagement.repository.ResourceProjectMappingRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectConfigService {

    private final ProjectConfigRepository projectConfigRepository;
    private final ResourceProjectMappingRepository resourceProjectMappingRepository;

    public ProjectConfigService(
            ProjectConfigRepository projectConfigRepository,
            ResourceProjectMappingRepository resourceProjectMappingRepository) {
        this.projectConfigRepository = projectConfigRepository;
        this.resourceProjectMappingRepository = resourceProjectMappingRepository;
    }

    @Transactional
    public ProjectConfigResponse createOrUpdate(ProjectConfigRequest req) {
        ProjectConfig entity = projectConfigRepository.findById(req.projectId())
                .orElse(new ProjectConfig());
        entity.setProjectId(req.projectId());
        entity.setProjectName(req.projectName());
        entity.setFullDayMinutes((int) (req.fullDayHours() * 60));
        entity.setHalfDayMinutes((int) (req.halfDayHours() * 60));
        entity.setLeaveFrequency(req.leaveFrequency());
        entity.setMaxLeavesPerPeriod(req.maxLeavesPerPeriod());
        projectConfigRepository.save(entity);
        return toResponse(entity);
    }

    @Transactional(readOnly = true)
    public ProjectConfigResponse getConfig(String projectId) {
        ProjectConfig entity = projectConfigRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
        return toResponse(entity);
    }

    @Transactional(readOnly = true)
    public List<ProjectConfigResponse> listConfigs() {
        return projectConfigRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void deleteConfig(String projectId) {
        ProjectConfig entity = projectConfigRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
        List<ResourceProjectMapping> linked = resourceProjectMappingRepository.findByProjectId(projectId);
        if (!linked.isEmpty()) {
            throw new BadRequestException(
                    "Cannot delete project '" + projectId + "': " + linked.size() + " resource(s) still linked");
        }
        projectConfigRepository.delete(entity);
    }

    @Transactional
    public ResourceProjectResponse assignResource(String projectId, ResourceProjectRequest req) {
        if (!projectConfigRepository.existsById(projectId)) {
            throw new NotFoundException("Project not found: " + projectId);
        }
        ResourceProjectMapping mapping = resourceProjectMappingRepository.findById(req.attendanceId())
                .orElse(new ResourceProjectMapping());
        mapping.setAttendanceId(req.attendanceId());
        mapping.setProjectId(projectId);
        mapping.setEmployeeName(req.employeeName());
        mapping.setEmail(req.email());
        mapping.setJoiningDate(req.joiningDate());
        resourceProjectMappingRepository.save(mapping);
        return new ResourceProjectResponse(
                mapping.getAttendanceId(),
                mapping.getEmployeeName(),
                mapping.getEmail(),
                mapping.getProjectId(),
                mapping.getJoiningDate());
    }

    @Transactional(readOnly = true)
    public List<ResourceProjectResponse> listResources(String projectId) {
        return resourceProjectMappingRepository.findByProjectId(projectId).stream()
                .map(m -> new ResourceProjectResponse(
                        m.getAttendanceId(), m.getEmployeeName(), m.getEmail(), m.getProjectId(), m.getJoiningDate()))
                .toList();
    }

    @Transactional
    public void removeResource(String attendanceId) {
        if (!resourceProjectMappingRepository.existsById(attendanceId)) {
            throw new NotFoundException("Resource not found: " + attendanceId);
        }
        resourceProjectMappingRepository.deleteById(attendanceId);
    }

    private ProjectConfigResponse toResponse(ProjectConfig c) {
        int count = resourceProjectMappingRepository.findByProjectId(c.getProjectId()).size();
        return new ProjectConfigResponse(
                c.getProjectId(),
                c.getProjectName(),
                c.getFullDayMinutes() / 60.0,
                c.getHalfDayMinutes() / 60.0,
                c.getLeaveFrequency(),
                c.getMaxLeavesPerPeriod(),
                count);
    }
}
