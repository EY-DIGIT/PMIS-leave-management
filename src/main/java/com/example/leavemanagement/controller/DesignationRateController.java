package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.DesignationRateResponse;
import com.example.leavemanagement.dto.DesignationRateUploadResult;
import com.example.leavemanagement.dto.ProjectYearMappingRow;
import com.example.leavemanagement.service.DesignationRateService;
import com.example.leavemanagement.service.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload and query designation rate cards — the standard monthly rates per "Role as per Contract"
 * for a given project and organisation.
 *
 * <p><strong>Upload order:</strong>
 * <ol>
 *   <li>POST /api/designation-rates/upload — upload the rate card with project start/end dates.
 *   <li>POST /api/resources/upload — then upload the resource master (roles are validated here).
 *   <li>POST /api/attendance/upload — then upload attendance sheets.
 * </ol>
 */
@RestController
@RequestMapping("/api/designation-rates")
@Tag(name = "Designation Rates", description = "Upload and query the designation rate card (role → year rates) per project/organisation")
public class DesignationRateController {

    private static final Logger log = LoggerFactory.getLogger(DesignationRateController.class);

    private final DesignationRateService designationRateService;
    private final FileStorageService fileStorageService;

    public DesignationRateController(
            DesignationRateService designationRateService,
            FileStorageService fileStorageService) {
        this.designationRateService = designationRateService;
        this.fileStorageService = fileStorageService;
    }

    @Operation(
            summary = "Upload designation rate card Excel",
            description = "Parses the rate card Excel (Role as per Contract | Base Rate) and upserts every "
                    + "role row for the given project and organisation. The Base Rate is the project Year-1 "
                    + "monthly rate; the later project years are generated as "
                    + "Year-N = round(baseRate × (1 + increasePercentage/100)^(N-1), 2), aligned to the "
                    + "project-year boundaries. "
                    + "Supply projectStartDate and projectEndDate to also store the project-year "
                    + "date-range mapping (Year-1 covers start → start+1yr−1day, etc.). "
                    + "Once stored, cost reports auto-select the correct rate year from the attendance "
                    + "date — no manual rateYear parameter is needed at attendance upload time. "
                    + "Must be done BEFORE uploading the resource master.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DesignationRateUploadResult> upload(
            @Parameter(description = "Project ID this rate card belongs to", required = true)
                    @RequestParam("projectId") String projectId,
            @Parameter(description = "Organisation ID this rate card belongs to", required = true)
                    @RequestParam("organisationId") String organisationId,
            @Parameter(description = "Project start date (ISO-8601, e.g. 2026-08-08). "
                            + "Required to auto-map attendance dates to rate years.")
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate projectStartDate,
            @Parameter(description = "Project end date (ISO-8601, e.g. 2033-08-08). "
                            + "Required to auto-map attendance dates to rate years.")
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate projectEndDate,
            @Parameter(description = "Annual rate increase percentage applied per project year "
                            + "(e.g. 5 → each year's rate is 5% above the previous year). Defaults to 0.")
                    @RequestParam(defaultValue = "0") double increasePercentage,
            @RequestPart("file") MultipartFile file) {

        DesignationRateUploadResult result = designationRateService.upload(
                file, projectId, organisationId, projectStartDate, projectEndDate, increasePercentage);
        try {
            fileStorageService.save(file, "designation-rates/" + organisationId + "/" + projectId);
        } catch (Exception e) {
            log.warn("Designation rate file could not be saved to storage: {}", e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @Operation(
            summary = "List designation rates for a project/organisation",
            description = "Returns all roles and their Year-1..Year-7 monthly rates for the given "
                    + "project and organisation, sorted alphabetically by role.")
    @GetMapping
    public List<DesignationRateResponse> getRates(
            @Parameter(description = "Project ID", required = true) @RequestParam("projectId") String projectId,
            @Parameter(description = "Organisation ID", required = true)
                    @RequestParam("organisationId") String organisationId) {
        return designationRateService.getRates(projectId, organisationId);
    }

    @Operation(
            summary = "List project-year date-range mappings",
            description = "Returns the Year-1..Year-7 effective date ranges computed from the project "
                    + "start/end dates supplied at rate card upload time. These ranges determine which "
                    + "rate year is applied automatically for any given attendance date.")
    @GetMapping("/year-mapping")
    public List<ProjectYearMappingRow> getYearMapping(
            @Parameter(description = "Project ID", required = true) @RequestParam("projectId") String projectId,
            @Parameter(description = "Organisation ID", required = true)
                    @RequestParam("organisationId") String organisationId) {
        return designationRateService.getYearMapping(projectId, organisationId);
    }

    @Operation(
            summary = "Resolve rate year(s) for a date range",
            description = "Returns the rate year(s) whose effective date range overlaps the supplied "
                    + "[startDate, endDate] window. A single quarter or month normally maps to one "
                    + "rate year; a range that straddles a year boundary returns two. "
                    + "Use this to determine which Year-N rate to apply before running cost reports.")
    @GetMapping("/rate-year")
    public List<ProjectYearMappingRow> resolveYearMapping(
            @Parameter(description = "Project ID", required = true) @RequestParam("projectId") String projectId,
            @Parameter(description = "Organisation ID", required = true)
                    @RequestParam("organisationId") String organisationId,
            @Parameter(description = "Range start date (ISO-8601)", required = true)
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Range end date (ISO-8601)", required = true)
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return designationRateService.resolveYearMapping(projectId, organisationId, startDate, endDate);
    }
}
