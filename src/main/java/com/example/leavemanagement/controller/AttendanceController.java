package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.ActivityAttendanceReportResult;
import com.example.leavemanagement.dto.ActivityReplacementReport;
import com.example.leavemanagement.dto.ActivityResourceDetailsReport;
import com.example.leavemanagement.dto.AttendanceReportResult;
import com.example.leavemanagement.dto.AttendanceUploadResult;
import com.example.leavemanagement.dto.EmployeeLeaveDetail;
import com.example.leavemanagement.dto.QuarterlyRelaxationRequest;
import com.example.leavemanagement.dto.RelaxationEligibilityResponse;
import com.example.leavemanagement.entity.LeaveRelaxation;
import com.example.leavemanagement.service.AttendanceQueryService;
import com.example.leavemanagement.service.FileStorageService;
import com.example.leavemanagement.service.LeaveReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

@RestController
@RequestMapping("/api/attendance")
@Tag(name = "Attendance", description = "Daily attendance upload, reports, and quarterly leave-policy settlement")
public class AttendanceController {

    private static final Logger log = LoggerFactory.getLogger(AttendanceController.class);

    private final AttendanceQueryService attendanceQueryService;
    private final LeaveReportService leaveReportService;
    private final FileStorageService fileStorageService;

    public AttendanceController(
            AttendanceQueryService attendanceQueryService,
            LeaveReportService leaveReportService,
            FileStorageService fileStorageService) {
        this.attendanceQueryService = attendanceQueryService;
        this.leaveReportService = leaveReportService;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Upload an attendance sheet for a period: persists one row per resource per working day.
     * POST /api/attendance/upload
     */
    @Operation(
            summary = "Upload an attendance sheet (Excel)",
            description = "Parses and stores the given Attendance Start Date/Attendance End Date period's "
                    + "attendance under 'milestoneId' and 'projectId': each weekday, non-holiday day becomes a "
                    + "P/HD (worked, per the project's full/half-day minute thresholds) or A (no punch data) "
                    + "row. Re-uploading a period replaces its rows. Every resource in the sheet must belong to "
                    + "'projectId' (checked against the master resource table) or the upload is rejected. "
                    + "Attendance Start Date must not be after Attendance End Date, and the sheet must have "
                    + "exactly as many day columns as the period has days, or the upload is rejected.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AttendanceUploadResult> upload(
            @Parameter(description = "Project id every resource in this upload must belong to")
                    @RequestParam("projectId") String projectId,
            @Parameter(description = "Organisation id this attendance upload belongs to")
                    @RequestParam("organisationId") String organisationId,
            @Parameter(description = "Milestone id this attendance upload belongs to") @RequestParam("milestoneId")
                    String milestoneId,
            @Parameter(description = "Activity id this attendance upload belongs to") @RequestParam(value = "activityId", required = false)
                    String activityId,
            @Parameter(description = "Attendance Start Date", example = "2026-07-01")
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Attendance End Date", example = "2026-07-31")
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "Rate card year (e.g. \"Year-1\") applied to every uploaded resource's "
                            + "active assignment on this project. Optional — omit to leave rate years untouched.")
                    @RequestParam(required = false) String rateYear,
            @Parameter(description = "Attendance Excel file (.xlsx/.xls)") @RequestPart("file") MultipartFile file) {
        // Save the raw file first — before parse consumes the InputStream and before validation
        // can reject the request — so the Excel is always archived for audit / re-processing.
        try {
            fileStorageService.saveAttendance(file, projectId, milestoneId, startDate, endDate);
        } catch (Exception e) {
            log.warn("Attendance file could not be saved to storage (NFS may be unavailable): {}", e.getMessage());
        }
        AttendanceUploadResult result =
                attendanceQueryService.upload(projectId, organisationId, milestoneId, activityId, startDate, endDate, rateYear, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Monthly dashboard: one summary per resource active on the project.
     * GET /api/attendance/report/monthly?projectId=&year=&month=
     */
    @Operation(
            summary = "Monthly attendance report (project dashboard)",
            description = "One AttendanceReportSummary per resource currently active on 'projectId', for the "
                    + "given month.")
    @GetMapping("/report/monthly")
    public AttendanceReportResult monthlyReport(
            @Parameter(description = "Project id") @RequestParam("projectId") String projectId,
            @Parameter(description = "Year", example = "2026") @RequestParam("year") int year,
            @Parameter(description = "Month (1-12)", example = "7") @RequestParam("month") int month) {
        return attendanceQueryService.monthlyReport(projectId, year, month);
    }

    /**
     * One resource's monthly summary.
     * GET /api/attendance/report/employee?resourceId=&year=&month=
     */
    @Operation(summary = "One resource's monthly attendance report")
    @GetMapping("/report/employee")
    public AttendanceReportResult employeeReport(
            @Parameter(description = "res_id (Attendance ID)") @RequestParam("resourceId") String resourceId,
            @Parameter(description = "Year", example = "2026") @RequestParam("year") int year,
            @Parameter(description = "Month (1-12)", example = "7") @RequestParam("month") int month) {
        return attendanceQueryService.employeeReport(resourceId, year, month);
    }

    /**
     * Yearly report: one resource (resourceId) or the whole project dashboard (projectId).
     * GET /api/attendance/report/yearly?projectId=&resourceId=&year=
     */
    @Operation(
            summary = "Yearly attendance report",
            description = "Pass resourceId for one resource's summary, or projectId for the project dashboard "
                    + "(one summary per active resource).")
    @GetMapping("/report/yearly")
    public AttendanceReportResult yearlyReport(
            @Parameter(description = "Project id (dashboard mode)") @RequestParam(required = false)
                    String projectId,
            @Parameter(description = "res_id (single-resource mode)") @RequestParam(required = false)
                    String resourceId,
            @Parameter(description = "Year", example = "2026") @RequestParam("year") int year) {
        return attendanceQueryService.yearlyReport(projectId, resourceId, year);
    }

    /**
     * Period-based attendance report scoped to an activity upload window.
     * GET /api/attendance/report/period?projectId=&startDate=&endDate=
     */
    @Operation(
            summary = "Attendance report for an activity upload period",
            description = "Returns one AttendanceReportSummary per resource active during the given "
                    + "date range. Leave (paid/unpaid) is computed using only the uploaded period while "
                    + "maintaining the cumulative quarter balance from prior uploads in the same quarter.")
    @GetMapping("/report/period")
    public AttendanceReportResult periodReport(
            @Parameter(description = "Project id") @RequestParam("projectId") String projectId,
            @Parameter(description = "Organisation id filter") @RequestParam(required = false) String organisationId,
            @Parameter(description = "Period start date", example = "2026-01-09")
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Period end date", example = "2026-02-08")
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return attendanceQueryService.periodReport(projectId, organisationId, startDate, endDate);
    }

    @Operation(
            summary = "Activity attendance report",
            description = "Returns the attendance report for the given project → milestone → activity. "
                    + "Only resources whose attendance was uploaded for this activity appear in the report. "
                    + "The window is the activity's own start/end date (fetched live from PMIS); the report "
                    + "period (reportStartDate/reportEndDate) expands automatically as more attendance is "
                    + "uploaded, up to the activity end date.")
    @GetMapping("/report/activity")
    public ActivityAttendanceReportResult activityReport(
            @Parameter(description = "Project id") @RequestParam String projectId,
            @Parameter(description = "Milestone id") @RequestParam String milestoneId,
            @Parameter(description = "Activity id from PMIS") @RequestParam String activityId) {
        return attendanceQueryService.activityReport(projectId, milestoneId, activityId);
    }

    /**
     * Resource-replacement summary for one activity, per designation.
     * GET /api/attendance/report/activity/replacements?projectId=&activityId=
     */
    @Operation(
            summary = "Activity resource-replacement report",
            description = "Per-designation replacement summary for the activity. A replacement is counted "
                    + "whenever more distinct resources worked a designation across the activity window than "
                    + "its configured quantity (replacementCount = distinctResourceCount - configuredQuantity). "
                    + "Derived live from uploaded attendance; 'totalReplacements' is the activity-wide total.")
    @GetMapping("/report/activity/replacements")
    public ActivityReplacementReport activityReplacements(
            @Parameter(description = "Project id") @RequestParam String projectId,
            @Parameter(description = "Activity id from PMIS") @RequestParam String activityId) {
        return attendanceQueryService.activityReplacements(projectId, activityId);
    }

    /**
     * Resource history for a project + designation (who worked which activities, when, and status).
     * GET /api/attendance/report/activity/resource-details?projectId=&designation=&organisationId=
     */
    @Operation(
            summary = "Resource-history by project + designation",
            description = "Lists every resource holding the given designation on the project, each with the "
                    + "activities they worked and the period worked on each (assignment window within the "
                    + "activity) plus status (Active/Completed). Used to decide whether an uploaded resource "
                    + "is a continuation, a new deployment, or a replacement. organisationId is an optional filter.")
    @GetMapping("/report/activity/resource-details")
    public ActivityResourceDetailsReport resourceDetailsByDesignation(
            @Parameter(description = "Project id") @RequestParam String projectId,
            @Parameter(description = "Designation (role as per contract)") @RequestParam String designation,
            @Parameter(description = "Organisation id filter (optional)")
                    @RequestParam(value = "organisationId", required = false) String organisationId) {
        return attendanceQueryService.resourceDetailsByDesignation(projectId, designation, organisationId);
    }

    /**
     * Per-employee activity leave dates: paid leave, half-day, and sandwich-charged dates.
     * GET /api/attendance/leave-dates?resourceId=E1&activityId=ACT-001
     */
    @Operation(
            summary = "Activity leave dates for one employee",
            description = "Returns the actual calendar dates of paid leaves, half-days, unpaid leaves, and "
                    + "sandwich-charged non-working days for the given resource over the activity window. "
                    + "Relaxation already applied is reflected in the scalar counters.")
    @GetMapping("/leave-dates")
    public EmployeeLeaveDetail leaveDates(
            @Parameter(description = "res_id of the employee") @RequestParam String resourceId,
            @Parameter(description = "Activity id from PMIS") @RequestParam String activityId,
            @Parameter(description = "Restrict to this project id") @RequestParam(required = false)
                    String projectId) {
        return leaveReportService.employeeDetail(resourceId, activityId, projectId);
    }

    /**
     * Returns which unpaid leave dates are still eligible for relaxation approval.
     * GET /api/attendance/relaxation/eligible-dates
     */
    @Operation(
            summary = "Eligible unpaid leave dates for relaxation",
            description = "Returns all unpaid leave dates for the given resource within the activity, split "
                    + "into already-approved dates and dates still available for relaxation selection.")
    @GetMapping("/relaxation/eligible-dates")
    public RelaxationEligibilityResponse eligibleRelaxationDates(
            @Parameter(description = "res_id of the resource") @RequestParam String resourceId,
            @Parameter(description = "Project id") @RequestParam String projectId,
            @Parameter(description = "Activity id from PMIS") @RequestParam String activityId) {
        return leaveReportService.eligibleRelaxationDates(resourceId, projectId, activityId);
    }

    /**
     * Records UIDAI's final leave-relaxation decision for one resource within an activity.
     * POST /api/attendance/relaxation (multipart/form-data)
     */
    @Operation(
            summary = "Record an activity leave-relaxation approval",
            description = "Approves the specified unpaid leave dates as relaxation leave. Each date must be "
                    + "an existing unpaid leave date for this resource within the activity and must not have "
                    + "been previously approved. Per-day cost = monthlyRate / calendar-days-in-that-month.")
    @PostMapping(value = "/relaxation", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmployeeLeaveDetail relaxation(
            @Parameter(description = "res_id of the resource") @RequestParam String resourceId,
            @Parameter(description = "Project id") @RequestParam String projectId,
            @Parameter(description = "Activity id from PMIS") @RequestParam String activityId,
            @Parameter(description = "Unpaid leave dates to approve (ISO-8601, e.g. 2026-02-20); "
                            + "repeat this parameter for multiple dates")
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) List<LocalDate> relaxationDates,
            @Parameter(description = "Reason / remarks") @RequestParam(required = false) String remarks,
            @Parameter(description = "Evidence file (PDF, image, etc.)")
                    @RequestPart(value = "attachment", required = false) MultipartFile attachment) {
        QuarterlyRelaxationRequest request =
                new QuarterlyRelaxationRequest(resourceId, projectId, activityId, relaxationDates, remarks);
        return leaveReportService.applyQuarterlyRelaxation(request, attachment);
    }

    /**
     * Downloads the evidence attachment for a recorded relaxation.
     * GET /api/attendance/relaxation/attachment?resourceId=&projectId=&activityId=
     */
    @Operation(summary = "Download the evidence attachment for a relaxation record")
    @GetMapping("/relaxation/attachment")
    public ResponseEntity<byte[]> relaxationAttachment(
            @Parameter(description = "res_id of the resource") @RequestParam String resourceId,
            @Parameter(description = "Project id") @RequestParam String projectId,
            @Parameter(description = "Activity id from PMIS") @RequestParam String activityId) {
        LeaveRelaxation relaxation =
                leaveReportService.getRelaxationAttachment(resourceId, projectId, activityId);
        String contentType = relaxation.getAttachmentContentType() != null
                ? relaxation.getAttachmentContentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        String filename = relaxation.getAttachmentName() != null
                ? relaxation.getAttachmentName()
                : "attachment";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        return new ResponseEntity<>(relaxation.getAttachmentData(), headers, HttpStatus.OK);
    }
}
