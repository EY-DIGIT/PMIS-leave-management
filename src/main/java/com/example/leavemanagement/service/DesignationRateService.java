package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.DesignationRateResponse;
import com.example.leavemanagement.dto.DesignationRateRow;
import com.example.leavemanagement.dto.DesignationRateUploadResult;
import com.example.leavemanagement.entity.DesignationRateMaster;
import com.example.leavemanagement.repository.DesignationRateMasterRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Manages the designation rate card — standard monthly rates per role per project/organisation.
 *
 * <p>The rate card must be uploaded <em>before</em> uploading the resource master for the same
 * project/organisation, because the resource upload validates each employee's
 * "Role as per Contract" against this table.
 */
@Service
public class DesignationRateService {

    private final DesignationRateParser parser;
    private final DesignationRateMasterRepository repository;

    public DesignationRateService(DesignationRateParser parser, DesignationRateMasterRepository repository) {
        this.parser = parser;
        this.repository = repository;
    }

    /**
     * Parses the rate card Excel and upserts every role row for the given project/organisation.
     * An existing row for the same (role, projectId, organisationId) is updated in place;
     * a new role creates a new row.
     */
    @Transactional
    public DesignationRateUploadResult upload(MultipartFile file, String projectId, String organisationId) {
        List<DesignationRateRow> rows = parser.parse(file);
        int upserted = 0;
        for (DesignationRateRow row : rows) {
            DesignationRateMaster entity = repository
                    .findByRoleAndProjectIdAndOrganisationId(row.role(), projectId, organisationId)
                    .orElseGet(() -> new DesignationRateMaster(row.role(), projectId, organisationId));
            entity.setRateCardByYear(row.rateCardByYear());
            repository.save(entity);
            upserted++;
        }
        return new DesignationRateUploadResult(projectId, organisationId, rows.size(), upserted);
    }

    /** Returns all roles defined for the given project/organisation, sorted alphabetically. */
    @Transactional(readOnly = true)
    public List<DesignationRateResponse> getRates(String projectId, String organisationId) {
        return repository.findByProjectIdAndOrganisationIdOrderByRoleAsc(projectId, organisationId)
                .stream()
                .map(e -> new DesignationRateResponse(
                        e.getId(), e.getRole(), e.getProjectId(), e.getOrganisationId(), e.getRateCardByYear()))
                .toList();
    }
}
