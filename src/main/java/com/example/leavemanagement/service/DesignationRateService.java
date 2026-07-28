package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.DesignationRateResponse;
import com.example.leavemanagement.dto.DesignationRateRow;
import com.example.leavemanagement.dto.DesignationRateUploadResult;
import com.example.leavemanagement.dto.ProjectYearMappingRow;
import com.example.leavemanagement.entity.DesignationRateMaster;
import com.example.leavemanagement.entity.ProjectYearMapping;
import com.example.leavemanagement.repository.DesignationRateMasterRepository;
import com.example.leavemanagement.repository.ProjectYearMappingRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Manages the designation rate card — standard monthly rates per role per project/organisation —
 * and the companion project-year-to-date-range mapping that auto-selects the correct rate year
 * from any attendance date.
 *
 * <p>The rate card must be uploaded <em>before</em> uploading the resource master for the same
 * project/organisation, because the resource upload validates each employee's role against this
 * table.
 */
@Service
public class DesignationRateService {

    private final DesignationRateParser parser;
    private final DesignationRateMasterRepository repository;
    private final ProjectYearMappingRepository yearMappingRepository;

    public DesignationRateService(
            DesignationRateParser parser,
            DesignationRateMasterRepository repository,
            ProjectYearMappingRepository yearMappingRepository) {
        this.parser = parser;
        this.repository = repository;
        this.yearMappingRepository = yearMappingRepository;
    }

    /**
     * Parses the rate card Excel and upserts every role row for the given project/organisation.
     * An existing row for the same (role, projectId, organisationId) is updated in place;
     * a new role creates a new row.
     *
     * <p>When {@code projectStartDate} and {@code projectEndDate} are supplied, the project-year
     * date ranges (Year-1 → effectiveFrom/To, Year-2 → ..., etc.) are computed and upserted into
     * {@code project_year_mapping}. These ranges enable automatic rate-year resolution during cost
     * report generation — no manual {@code rateYear} parameter is needed at attendance upload time.
     */
    @Transactional
    public DesignationRateUploadResult upload(
            MultipartFile file, String projectId, String organisationId,
            LocalDate projectStartDate, LocalDate projectEndDate) {
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

        List<ProjectYearMappingRow> yearMappings = List.of();
        if (projectStartDate != null && projectEndDate != null) {
            yearMappings = upsertYearMappings(projectId, organisationId, projectStartDate, projectEndDate);
        }

        return new DesignationRateUploadResult(projectId, organisationId, rows.size(), upserted, yearMappings);
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

    /** Returns the project-year-to-date-range mappings for the given project/organisation. */
    @Transactional(readOnly = true)
    public List<ProjectYearMappingRow> getYearMapping(String projectId, String organisationId) {
        return yearMappingRepository
                .findByProjectIdAndOrganisationIdOrderByEffectiveFromAsc(projectId, organisationId)
                .stream()
                .map(m -> new ProjectYearMappingRow(m.getRateYear(), m.getEffectiveFrom(), m.getEffectiveTo()))
                .toList();
    }

    /**
     * Returns the rate year(s) whose effective date range overlaps [startDate, endDate].
     * A typical billing period (quarter, month) spans exactly one year, but straddles are
     * possible for date ranges that cross a year boundary.
     */
    @Transactional(readOnly = true)
    public List<ProjectYearMappingRow> resolveYearMapping(
            String projectId, String organisationId, LocalDate startDate, LocalDate endDate) {
        return yearMappingRepository
                .findOverlapping(projectId, organisationId, startDate, endDate)
                .stream()
                .map(m -> new ProjectYearMappingRow(m.getRateYear(), m.getEffectiveFrom(), m.getEffectiveTo()))
                .toList();
    }

    /**
     * Computes Year-1..Year-7 date ranges from the project start/end dates and upserts them.
     *
     * <p>Each year starts exactly one calendar year after the previous one:
     * <ul>
     *   <li>Year-1: startDate → startDate + 1yr − 1day
     *   <li>Year-2: startDate + 1yr → startDate + 2yr − 1day
     *   <li>…
     *   <li>Year-N (last): startDate + (N-1)yr → endDate (may be shorter than a full year)
     * </ul>
     */
    private List<ProjectYearMappingRow> upsertYearMappings(
            String projectId, String organisationId, LocalDate startDate, LocalDate endDate) {
        List<ProjectYearMappingRow> result = new ArrayList<>();
        for (int year = 1; year <= 7; year++) {
            LocalDate from = startDate.plusYears(year - 1);
            if (from.isAfter(endDate)) break;

            LocalDate nextYearStart = startDate.plusYears(year);
            LocalDate to = nextYearStart.isAfter(endDate) ? endDate : nextYearStart.minusDays(1);
            String rateYear = "Year-" + year;

            ProjectYearMapping mapping = yearMappingRepository
                    .findByProjectIdAndOrganisationIdAndRateYear(projectId, organisationId, rateYear)
                    .orElseGet(() -> new ProjectYearMapping(
                            projectId, organisationId, rateYear, from, to));
            mapping.setEffectiveFrom(from);
            mapping.setEffectiveTo(to);
            yearMappingRepository.save(mapping);

            result.add(new ProjectYearMappingRow(rateYear, from, to));
            if (to.equals(endDate)) break; // last (possibly partial) year reached
        }
        return result;
    }
}
