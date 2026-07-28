package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.AttendanceReportResult;
import com.example.leavemanagement.dto.AttendanceUploadResult;
import com.example.leavemanagement.dto.EmployeeLeaveDetail;
import com.example.leavemanagement.dto.QuarterLeaveReport;
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
     * Per-employee quarterly leave dates: paid leave, half-day, and sandwich-charged dates.
     * GET /api/attendance/leave-dates?resourceId=E1&year=2026&quarter=3
     */
    @Operation(
            summary = "Quarterly leave dates for one employee",
            description = "Returns the actual calendar dates of paid leaves, half-days, unpaid leaves, and "
                    + "sandwich-charged non-working days for the given resource and quarter. Relaxation already "
                    + "applied is reflected in the scalar counters; the date lists are not affected by relaxation "
                    + "(sandwich and unpaid dates are still the raw policy output). "
                    + "Calendar quarters: Q1 Jan-Mar, Q2 Apr-Jun, Q3 Jul-Sep, Q4 Oct-Dec.")
    @GetMapping("/leave-dates")
    public EmployeeLeaveDetail leaveDates(
            @Parameter(description = "res_id of the employee") @RequestParam String resourceId,
            @Parameter(description = "Year", example = "2026") @RequestParam int year,
            @Parameter(description = "Quarter (1-4)", example = "3") @RequestParam int quarter,
            @Parameter(description = "Restrict to this project id") @RequestParam(required = false)
                    String projectId) {
        return leaveReportService.employeeDetail(resourceId, year, quarter, projectId);
    }

    /**
     * Returns which unpaid leave dates are still eligible for relaxation approval.
     * GET /api/attendance/quarterly-relaxation/eligible-dates
     */
    @Operation(
            summary = "Eligible unpaid leave dates for relaxation",
            description = "Returns all unpaid leave dates for the given resource and quarter, split "
                    + "into already-approved dates and dates still available for relaxation selection.")
    @GetMapping("/quarterly-relaxation/eligible-dates")
    public RelaxationEligibilityResponse eligibleRelaxationDates(
            @Parameter(description = "res_id of the resource") @RequestParam String resourceId,
            @Parameter(description = "Project id") @RequestParam String projectId,
            @Parameter(description = "Year", example = "2026") @RequestParam int year,
            @Parameter(description = "Quarter (1-4)", example = "3") @RequestParam int quarter) {
        return leaveReportService.eligibleRelaxationDates(resourceId, projectId, year, quarter);
    }

    /**
     * Records UIDAI's final leave-relaxation decision for one resource's quarter.
     * POST /api/attendance/quarterly-relaxation (multipart/form-data)
     * Pass each selected unpaid leave date as a separate {@code relaxationDates} value (ISO-8601,
     * e.g. 2026-02-20). An optional evidence file may be attached as the "attachment" part.
     */
    @Operation(
            summary = "Record a quarterly leave-relaxation approval",
            description = "Approves the specified unpaid leave dates as relaxation leave. Each date must be "
                    + "an existing unpaid leave date for this resource's quarter and must not have been "
                    + "previously approved. Per-day cost = monthlyRate / calendar-days-in-that-month, so "
                    + "dates from different months are priced independently. Returns the recalculated "
                    + "quarterly settlement showing the updated relaxationLeave and unpaidLeave.")
    @PostMapping(value = "/quarterly-relaxation", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmployeeLeaveDetail quarterlyRelaxation(
            @Parameter(description = "res_id of the resource") @RequestParam String resourceId,
            @Parameter(description = "Project id") @RequestParam String projectId,
            @Parameter(description = "Year", example = "2026") @RequestParam int year,
            @Parameter(description = "Quarter (1-4)", example = "3") @RequestParam int quarter,
            @Parameter(description = "Unpaid leave dates to approve (ISO-8601, e.g. 2026-02-20); "
                            + "repeat this parameter for multiple dates")
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) List<LocalDate> relaxationDates,
            @Parameter(description = "Reason / remarks") @RequestParam(required = false) String remarks,
            @Parameter(description = "Evidence file (PDF, image, etc.)")
                    @RequestPart(value = "attachment", required = false) MultipartFile attachment) {
        QuarterlyRelaxationRequest request =
                new QuarterlyRelaxationRequest(resourceId, projectId, year, quarter, relaxationDates, remarks);
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
