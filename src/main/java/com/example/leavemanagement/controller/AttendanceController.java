package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.AttendanceReportResult;
import com.example.leavemanagement.dto.AttendanceUploadResult;
import com.example.leavemanagement.dto.EmployeeLeaveDetail;
import com.example.leavemanagement.dto.QuarterLeaveReport;
import com.example.leavemanagement.dto.QuarterlyRelaxationRequest;
import com.example.leavemanagement.dto.ResourceCostResult;
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
                attendanceQueryService.upload(projectId, milestoneId, activityId, startDate, endDate, rateYear, file);
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
     * Quarterly report: one resource (resourceId) or the whole project dashboard (projectId).
     * GET /api/attendance/report/quarterly?projectId=&resourceId=&year=&quarter=
     */
    @Operation(
            summary = "Quarterly attendance report",
            description = "Pass resourceId for one resource's summary, or projectId for the project dashboard "
                    + "(one summary per active resource). Calendar quarters: Q1 Jan-Mar, Q2 Apr-Jun, Q3 Jul-Sep, "
                    + "Q4 Oct-Dec.")
    @GetMapping("/report/quarterly")
    public AttendanceReportResult quarterlyReport(
            @Parameter(description = "Project id (dashboard mode)") @RequestParam(required = false)
                    String projectId,
            @Parameter(description = "res_id (single-resource mode)") @RequestParam(required = false)
                    String resourceId,
            @Parameter(description = "Year", example = "2026") @RequestParam("year") int year,
            @Parameter(description = "Quarter (1-4)", example = "3") @RequestParam("quarter") int quarter) {
        return attendanceQueryService.quarterlyReport(projectId, resourceId, year, quarter);
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
     * Quarterly leave-policy settlement (UIDAI 5.24.1) over stored attendance.
     * GET /api/attendance/quarterly-leave?year=2026&quarter=2
     */
    @Operation(
            summary = "Quarterly leave-policy settlement (UIDAI 5.24.1)",
            description = "Applies the policy over the quarter's stored attendance: up to 6 paid leave days "
                    + "(pro-rata for mid-quarter joiners), the rest unpaid, with sandwich-leave weekend/holiday "
                    + "charging. Calendar quarters: Q1 Jan-Mar, Q2 Apr-Jun, Q3 Jul-Sep, Q4 Oct-Dec. Pass "
                    + "projectId to restrict to resources uploaded under that project.")
    @GetMapping("/quarterly-leave")
    public QuarterLeaveReport quarterlyLeave(
            @Parameter(description = "Year", example = "2026") @RequestParam("year") int year,
            @Parameter(description = "Quarter (1-4)", example = "2") @RequestParam("quarter") int quarter,
            @Parameter(description = "Restrict to this project id") @RequestParam(required = false)
                    String projectId) {
        return attendanceQueryService.quarterlySettlement(year, quarter, projectId);
    }

    /**
     * Records UIDAI's final leave-relaxation decision for one resource's quarter.
     * POST /api/attendance/quarterly-relaxation (multipart/form-data)
     * An optional evidence file (PDF, image, etc.) may be attached as the "attachment" part.
     */
    @Operation(
            summary = "Record a quarterly leave-relaxation approval",
            description = "Each call adds relaxationDays to the running cumulative total for this "
                    + "resource/project/quarter, moving that many days from unpaid leave into relaxation leave. "
                    + "Paid leave is never changed. The increment is automatically clamped to the remaining "
                    + "unpaid leave so it is impossible to approve more than exists. An optional evidence file "
                    + "can be attached as the 'attachment' part. Returns the recalculated quarterly settlement "
                    + "showing the updated relaxationLeave and unpaidLeave.")
    @PostMapping(value = "/quarterly-relaxation", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmployeeLeaveDetail quarterlyRelaxation(
            @Parameter(description = "res_id of the resource") @RequestParam String resourceId,
            @Parameter(description = "Project id") @RequestParam String projectId,
            @Parameter(description = "Year", example = "2026") @RequestParam int year,
            @Parameter(description = "Quarter (1-4)", example = "3") @RequestParam int quarter,
            @Parameter(description = "Days to move from unpaid → relaxation (supports 0.5)", example = "0.5")
                    @RequestParam double relaxationDays,
            @Parameter(description = "Reason / remarks") @RequestParam(required = false) String remarks,
            @Parameter(description = "Evidence file (PDF, image, etc.)")
                    @RequestPart(value = "attachment", required = false) MultipartFile attachment) {
        QuarterlyRelaxationRequest request =
                new QuarterlyRelaxationRequest(resourceId, projectId, year, quarter, relaxationDays, remarks);
        return leaveReportService.applyQuarterlyRelaxation(request, attachment);
    }

    /**
     * Downloads the evidence attachment for a recorded relaxation.
     * GET /api/attendance/quarterly-relaxation/attachment?resourceId=&projectId=&year=&quarter=
     */
    @Operation(summary = "Download the evidence attachment for a relaxation record")
    @GetMapping("/quarterly-relaxation/attachment")
    public ResponseEntity<byte[]> relaxationAttachment(
            @Parameter(description = "res_id of the resource") @RequestParam String resourceId,
            @Parameter(description = "Project id") @RequestParam String projectId,
            @Parameter(description = "Year", example = "2026") @RequestParam int year,
            @Parameter(description = "Quarter (1-4)", example = "3") @RequestParam int quarter) {
        LeaveRelaxation relaxation =
                leaveReportService.getRelaxationAttachment(resourceId, projectId, year, quarter);
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
