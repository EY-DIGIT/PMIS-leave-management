package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.MonthlyAttendanceStored;
import com.example.leavemanagement.dto.MonthlyAttendanceSummary;
import com.example.leavemanagement.dto.QuarterLeaveReport;
import com.example.leavemanagement.service.AttendanceQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
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

@RestController
@RequestMapping("/api/attendance")
@Tag(name = "Attendance", description = "Monthly summaries and quarterly leave-policy settlement from attendance sheets")
public class AttendanceController {

    private final AttendanceQueryService attendanceQueryService;

    public AttendanceController(AttendanceQueryService attendanceQueryService) {
        this.attendanceQueryService = attendanceQueryService;
    }

    /**
     * Upload a monthly attendance sheet: stores it and returns the summary.
     * The stored data then feeds the GET summary and the quarterly settlement.
     * POST /api/attendance/summary
     */
    @Operation(
            summary = "Upload a monthly attendance summary (Excel)",
            description = "Upload the monthly attendance .xlsx/.xls and get back: the count of Saturdays, "
                    + "Sundays and public holidays in the month, and for each employee the number of leaves "
                    + "taken (In=0 and Out=0 on a working day) and the number of worked days under 8 hours "
                    + "(from In-Time and Out-Time). The month is also persisted, so GET /api/attendance/summary "
                    + "and the quarterly settlement can read it. If startDate/endDate are given, they must span "
                    + "a complete month (1st-to-last-day of a calendar month, or the same date one month later) "
                    + "or the upload is rejected.")
    @PostMapping(value = "/summary", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MonthlyAttendanceSummary summary(
            @Parameter(description = "Month the sheet covers (1-12)", example = "6") @RequestParam("month") int month,
            @Parameter(description = "Year the sheet covers", example = "2026") @RequestParam("year") int year,
            @Parameter(description = "Milestone id this attendance upload belongs to") @RequestParam("milestoneId")
                    String milestoneId,
            @Parameter(description = "Period start date — must, with endDate, span a complete month")
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Period end date — must, with startDate, span a complete month")
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "Attendance Excel file (.xlsx/.xls)") @RequestPart("file") MultipartFile file) {
        return attendanceQueryService.storeAndSummarize(year, month, milestoneId, startDate, endDate, file);
    }

    /**
     * Persist a monthly attendance sheet so it can be queried later (monthly
     * summary, quarterly settlement). POST /api/attendance/monthly
     */
    @Operation(
            summary = "Store a monthly attendance sheet",
            description = "Parses and saves the month's attendance (absent weekdays + worked minutes per day). "
                    + "Re-uploading the same month overwrites it. Required before the GET summary / quarterly "
                    + "settlement can read it. If startDate/endDate are given, they must span a complete month "
                    + "(1st-to-last-day of a calendar month, or the same date one month later) or the upload "
                    + "is rejected.")
    @PostMapping(value = "/monthly", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MonthlyAttendanceStored> storeMonthly(
            @Parameter(description = "Month the sheet covers (1-12)", example = "6") @RequestParam("month") int month,
            @Parameter(description = "Year the sheet covers", example = "2026") @RequestParam("year") int year,
            @Parameter(description = "Milestone id this attendance upload belongs to") @RequestParam("milestoneId")
                    String milestoneId,
            @Parameter(description = "Period start date — must, with endDate, span a complete month")
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Period end date — must, with startDate, span a complete month")
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "Attendance Excel file (.xlsx/.xls)") @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attendanceQueryService.storeMonthly(year, month, milestoneId, startDate, endDate, file));
    }

    /**
     * Monthly summary from previously stored attendance — no file upload.
     * GET /api/attendance/summary?year=2026&month=6
     */
    @Operation(
            summary = "Attendance summary (from stored data)",
            description = "Read from attendance previously stored via the upload: Saturday/Sunday/public-holiday "
                    + "counts plus per-employee leaves taken and worked days under 8 hours. month=1..12 returns a "
                    + "single MonthlyAttendanceSummary; month=all (the default) returns an AttendanceSummaryReport "
                    + "with one summary per stored month of the year.")
    @GetMapping("/summary")
    public Object monthlySummary(
            @Parameter(description = "Year", example = "2026") @RequestParam("year") int year,
            @Parameter(description = "Month 1-12, or 'all' for every stored month", example = "all")
                    @RequestParam(name = "month", required = false, defaultValue = "all") String month) {
        return attendanceQueryService.summary(year, month);
    }

    /**
     * Quarterly leave-policy settlement (UIDAI 5.24.1) over stored attendance.
     * GET /api/attendance/quarterly-leave?year=2026&quarter=2
     */
    @Operation(
            summary = "Quarterly leave-policy settlement (UIDAI 5.24.1)",
            description = "Applies the policy over the quarter's stored attendance: up to 6 paid leave days "
                    + "(pro-rata for mid-quarter joiners), the rest unpaid, with sandwich-leave weekend/holiday "
                    + "charging. Calendar quarters: Q1 Jan-Mar, Q2 Apr-Jun, Q3 Jul-Sep, Q4 Oct-Dec.")
    @GetMapping("/quarterly-leave")
    public QuarterLeaveReport quarterlyLeave(
            @Parameter(description = "Year", example = "2026") @RequestParam("year") int year,
            @Parameter(description = "Quarter (1-4)", example = "2") @RequestParam("quarter") int quarter) {
        return attendanceQueryService.quarterlySettlement(year, quarter);
    }
}
