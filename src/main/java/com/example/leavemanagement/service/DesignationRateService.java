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
    private final ResourceBasedPeriodService resourceBasedPeriodService;

    public DesignationRateService(
            DesignationRateParser parser,
            DesignationRateMasterRepository repository,
            ProjectYearMappingRepository yearMappingRepository,
            ResourceBasedPeriodService resourceBasedPeriodService) {
        this.parser = parser;
        this.repository = repository;
        this.yearMappingRepository = yearMappingRepository;
        this.resourceBasedPeriodService = resourceBasedPeriodService;
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
            LocalDate projectStartDate, LocalDate projectEndDate, double increasePercentage) {
        List<DesignationRateRow> rows = parser.parse(file);

        // The rate-year windows are anchored to the RESOURCE-BASED PHASE start, not the project start:
        // billing only begins once the resource-based phase starts, so rate Year-1 (the base rate) must
        // apply from that date and step yearly until the resource-based period ends. Fall back to the
        // supplied project dates only when the phases are unavailable (mock off / call failed).
        LocalDate anchorStart = projectStartDate;
        LocalDate anchorEnd = projectEndDate;
        var resourceBased = resourceBasedPeriodService.resolve(projectId);
        if (resourceBased.isPresent()) {
            anchorStart = resourceBased.get().from();
            anchorEnd = resourceBased.get().to();
        }

        // Compute the year boundaries first — the generated rate card is aligned to these rateYear keys
        // ("Year-1"…"Year-N"). When no anchor dates are available we fall back to a plain seven-year card
        // so the rate resolver still finds a value.
        List<ProjectYearMappingRow> yearMappings = List.of();
        if (anchorStart != null && anchorEnd != null) {
            yearMappings = upsertYearMappings(projectId, organisationId, anchorStart, anchorEnd);
        }
        List<String> rateYears = yearMappings.isEmpty()
                ? defaultRateYears()
                : yearMappings.stream().map(ProjectYearMappingRow::rateYear).toList();

        int upserted = 0;
        for (DesignationRateRow row : rows) {
            DesignationRateMaster entity = repository
                    .findByRoleAndProjectIdAndOrganisationId(row.role(), projectId, organisationId)
                    .orElseGet(() -> new DesignationRateMaster(row.role(), projectId, organisationId));
            entity.setRateCardByYear(generateRateCard(row.baseRate(), increasePercentage, rateYears));
            repository.save(entity);
            upserted++;
        }

        return new DesignationRateUploadResult(projectId, organisationId, rows.size(), upserted, yearMappings);
    }

    /**
     * Generates a rate-year → monthly-rate map from a base (project Year-1) rate and a fixed annual
     * increase percentage: {@code Year-N = round(baseRate × (1 + inc/100)^(N-1), 2)}. The first year
     * (index 0) is exactly the base rate; each subsequent year compounds the increase once.
     */
    private static java.util.Map<String, Double> generateRateCard(
            double baseRate, double increasePercentage, List<String> rateYears) {
        java.util.Map<String, Double> card = new java.util.LinkedHashMap<>();
        double factor = 1 + (increasePercentage / 100.0);
        for (int i = 0; i < rateYears.size(); i++) {
            double rate = baseRate * Math.pow(factor, i);
            card.put(rateYears.get(i), Math.round(rate * 100.0) / 100.0);
        }
        return card;
    }

    private static List<String> defaultRateYears() {
        List<String> years = new ArrayList<>();
        for (int y = 1; y <= 7; y++) years.add("Year-" + y);
        return years;
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
        // Clear any previously computed windows first so a changed anchor (e.g. project start →
        // resource-based start) leaves no stale rows that would overlap and break findEffectiveOn.
        yearMappingRepository.deleteByProjectIdAndOrganisationId(projectId, organisationId);
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
