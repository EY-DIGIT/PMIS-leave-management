package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.ResourceResponse;
import com.example.leavemanagement.dto.ResourceUpdateRequest;
import com.example.leavemanagement.dto.ResourceUploadResult;
import com.example.leavemanagement.service.MasterResourceService;
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

    private final MasterResourceService masterResourceService;

    public MasterResourceController(MasterResourceService masterResourceService) {
        this.masterResourceService = masterResourceService;
    }

    /**
     * Upload the resource master Excel: upserts every row by res_id, all under the given project.
     * POST /api/resources/upload (multipart/form-data)
     */
    @Operation(
            summary = "Upload the resource master Excel",
            description = "Upload an .xlsx/.xls file whose first sheet has a header row followed by rows of "
                    + "[res_id, name, emailId, rate_card, date_of_joining, last_date, designationType, isactive]. "
                    + "Every resource in the file is assigned to 'projectId'. Each row is upserted by res_id — "
                    + "re-uploading updates existing resources.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResourceUploadResult> upload(
            @Parameter(description = "Project id every resource in this upload belongs to")
                    @RequestParam("projectId") String projectId,
            @Parameter(description = "Resource master Excel file (.xlsx/.xls)") @RequestPart("file")
                    MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(masterResourceService.upload(file, projectId));
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
            @Parameter(description = "Filter by active/inactive") @RequestParam(required = false) Boolean active,
            @Parameter(description = "date_of_joining >= this date")
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
            @Parameter(description = "date_of_joining <= this date")
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo) {
        return masterResourceService.search(
                resId, name, emailId, designationType, projectId, active, joinedFrom, joinedTo);
    }

    /** Get a single resource by res_id. GET /api/resources/{resId} */
    @Operation(summary = "Get a single resource", description = "Returns 404 if not found.")
    @GetMapping("/{resId}")
    public ResourceResponse get(@PathVariable String resId) {
        return masterResourceService.getResource(resId);
    }

    /** Update a resource. PUT /api/resources/{resId} */
    @Operation(
            summary = "Update a resource",
            description = "Updates name, emailId, rateCard, dateOfJoining, lastDate, designationType and active. "
                    + "Returns 404 if the res_id doesn't exist.")
    @PutMapping("/{resId}")
    public ResourceResponse update(@PathVariable String resId, @Valid @RequestBody ResourceUpdateRequest request) {
        return masterResourceService.updateResource(resId, request);
    }
}
