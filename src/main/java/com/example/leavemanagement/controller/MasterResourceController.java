package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.ResourceResponse;
import com.example.leavemanagement.dto.ResourceUpdateRequest;
import com.example.leavemanagement.dto.ResourceUploadResult;
import com.example.leavemanagement.service.FileStorageService;
import com.example.leavemanagement.service.MasterResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/resources")
@Tag(name = "Resources", description = "Master resource (workforce) table: upload, search, get and update")
public class MasterResourceController {

    private static final Logger log = LoggerFactory.getLogger(MasterResourceController.class);

    private final MasterResourceService masterResourceService;
    private final FileStorageService fileStorageService;

    public MasterResourceController(
            MasterResourceService masterResourceService,
            FileStorageService fileStorageService) {
        this.masterResourceService = masterResourceService;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Upload the resource master Excel: upserts every row by res_id, all under the given project.
     * POST /api/resources/upload (multipart/form-data)
     */
    @Operation(
            summary = "Upload the resource master Excel",
            description = "Upload an .xlsx/.xls file whose first sheet has two header rows (group headings, "
                    + "then column labels) followed by rows of [Attendance ID, Employee Name, Role as per "
                    + "Contract, Location, Date of Joining, Last Day of Working, Year-1..Year-7 rate card, "
                    + "Category (RFP/CCN/ASG), CCN/ASG details]. A resource is active when Last Day of Working "
                    + "is blank. Every resource in the file is assigned to 'projectId'. A row with the same "
                    + "designation as the resource's current stint updates it in place; a row with a "
                    + "different designation closes the current stint (its last day becomes the day before "
                    + "the new row's date of joining) and opens a new one, preserving designation history.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResourceUploadResult> upload(
            @Parameter(description = "Project id every resource in this upload belongs to")
                    @RequestParam("projectId") String projectId,
            @Parameter(description = "Organisation id — used to validate roles against the designation rate master")
                    @RequestParam("organisationId") String organisationId,
            @Parameter(description = "Resource master Excel file (.xlsx/.xls)") @RequestPart("file")
                    MultipartFile file) {
        ResourceUploadResult result = masterResourceService.upload(file, projectId, organisationId);
        try {
            fileStorageService.save(file, "resources/" + projectId);
        } catch (Exception e) {
            log.warn("Resource file could not be saved to storage (NFS may be unavailable): {}", e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Search resources with all-optional filters.
     * GET /api/resources?projectId=&name=&emailId=&designationType=&active=&joinedFrom=&joinedTo=
     */
    @Operation(
            summary = "Search resources",
            description = "All parameters are optional and combine with AND. name/emailId are case-insensitive "
                    + "contains matches; resId/designationType/projectId/active are exact matches; "
                    + "joinedFrom/joinedTo bound date_of_joining (inclusive). Pass projectId to get only the "
                    + "resources assigned to that project instead of every resource.")
    @GetMapping
    public List<ResourceResponse> search(
            @Parameter(description = "Exact res_id") @RequestParam(required = false) String resId,
            @Parameter(description = "Name contains (case-insensitive)") @RequestParam(required = false) String name,
            @Parameter(description = "Email contains (case-insensitive)")
                    @RequestParam(required = false) String emailId,
            @Parameter(description = "Exact designation type") @RequestParam(required = false)
                    String designationType,
            @Parameter(description = "Exact project id — only resources assigned to this project")
                    @RequestParam(required = false) String projectId,
            @Parameter(description = "Exact organisation id — only resources assigned to this organisation")
                    @RequestParam(required = false) String organisationId,
            @Parameter(description = "Filter by active/inactive") @RequestParam(required = false) Boolean active,
            @Parameter(description = "date_of_joining >= this date")
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
            @Parameter(description = "date_of_joining <= this date")
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo) {
        return masterResourceService.search(
                resId, name, emailId, designationType, projectId, organisationId, active, joinedFrom, joinedTo);
    }

    /**
     * Get the distinct rate card years configured (no amounts) across a project's active resources.
     * GET /api/resources/rate-cards?projectId=P1
     */
    @Operation(
            summary = "Get rate card years by project",
            description = "Returns the distinct rate-card years configured (e.g. [\"Year-1\", \"Year-2\", "
                    + "...]) across every currently active resource assigned to 'projectId' — one flat list, "
                    + "no amounts, not broken out per resource. Empty list if the project has no active "
                    + "resources.")
    @GetMapping("/rate-cards")
    public List<String> rateCards(
            @Parameter(description = "Project id to list rate card years for") @RequestParam("projectId")
                    String projectId) {
        return masterResourceService.getRateCardsByProject(projectId);
    }

    /**
     * Get a resource's current stint (falls back to its most recent stint if none is active).
     * GET /api/resources/{resId}
     */
    @Operation(
            summary = "Get a resource's current details",
            description = "Returns the resource's active employment stint, or its most recent stint if none "
                    + "is currently active. Returns 404 if the res_id has no stints at all.")
    @GetMapping("/{resId}")
    public ResourceResponse get(@PathVariable String resId) {
        return masterResourceService.getResource(resId);
    }

    /**
     * Get every historical stint (designation/employment history) for a resource.
     * GET /api/resources/{resId}/history
     */
    @Operation(
            summary = "Get a resource's full designation/employment history",
            description = "Returns every stint for this res_id, oldest first — one entry per designation "
                    + "change or resignation/rejoin. Empty list if the res_id is unknown.")
    @GetMapping("/{resId}/history")
    public List<ResourceResponse> history(@PathVariable String resId) {
        return masterResourceService.getHistory(resId);
    }

    /** Update a resource's current stint in place. PUT /api/resources/{resId} */
    @Operation(
            summary = "Update a resource's current stint",
            description = "Updates the resource's currently active stint in place (name, emailId, rate card, "
                    + "dates, designation, category, active, project). Does not trigger designation-history "
                    + "logic — use the Excel upload for designation changes/resignation/rejoin. Returns 404 "
                    + "if the res_id has no active stint.")
    @PutMapping("/{resId}")
    public ResourceResponse update(@PathVariable String resId, @Valid @RequestBody ResourceUpdateRequest request) {
        return masterResourceService.updateResource(resId, request);
    }
}
