package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.DesignationRateResponse;
import com.example.leavemanagement.dto.DesignationRateUploadResult;
import com.example.leavemanagement.service.DesignationRateService;
import com.example.leavemanagement.service.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li>POST /api/designation-rates/upload — upload the rate card first.
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
            description = "Parses the rate card Excel (Role as per Contract | Year-1 Rate | … | Year-7 Rate) "
                    + "and upserts every role row for the given project and organisation. "
                    + "Must be done BEFORE uploading the resource master — the resource upload "
                    + "validates each employee's role against this table.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DesignationRateUploadResult> upload(
            @Parameter(description = "Project ID this rate card belongs to", required = true)
                    @RequestParam("projectId") String projectId,
            @Parameter(description = "Organisation ID this rate card belongs to", required = true)
                    @RequestParam("organisationId") String organisationId,
            @RequestPart("file") MultipartFile file) {

        DesignationRateUploadResult result = designationRateService.upload(file, projectId, organisationId);
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
}
