package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.ResourceProjectMapping;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceProjectMappingRepository extends JpaRepository<ResourceProjectMapping, String> {

    List<ResourceProjectMapping> findByProjectId(String projectId);

    void deleteByProjectId(String projectId);
}
