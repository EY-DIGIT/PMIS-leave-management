package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.MasterResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** {@link JpaSpecificationExecutor} backs the multi-parameter search on GET /api/resources. */
public interface MasterResourceRepository
        extends JpaRepository<MasterResource, String>, JpaSpecificationExecutor<MasterResource> {}
