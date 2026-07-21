package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.DesignationRateMaster;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DesignationRateMasterRepository extends JpaRepository<DesignationRateMaster, Long> {

    Optional<DesignationRateMaster> findByRoleAndProjectIdAndOrganisationId(
            String role, String projectId, String organisationId);

    List<DesignationRateMaster> findByProjectIdAndOrganisationIdOrderByRoleAsc(
            String projectId, String organisationId);

    boolean existsByRoleAndProjectIdAndOrganisationId(
            String role, String projectId, String organisationId);
}
