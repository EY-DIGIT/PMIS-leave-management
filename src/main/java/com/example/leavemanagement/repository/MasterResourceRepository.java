package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.MasterResource;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** {@link JpaSpecificationExecutor} backs the multi-parameter search on GET /api/resources. */
public interface MasterResourceRepository
        extends JpaRepository<MasterResource, Long>, JpaSpecificationExecutor<MasterResource> {

    /** res_id is the unique business key — at most one master_resource row per resource. */
    Optional<MasterResource> findByResId(String resId);
}
