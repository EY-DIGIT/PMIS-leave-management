package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.ProjectConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectConfigRepository extends JpaRepository<ProjectConfig, Long> {

    Optional<ProjectConfig> findByProjectIdAndOrganisationId(String projectId, String organisationId);
}
