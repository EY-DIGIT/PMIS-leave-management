package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.MonthlyResourceCost;
import com.example.leavemanagement.dto.ResourceCostResult;
import java.time.LocalDate;
import java.util.List;
import com.example.leavemanagement.service.AttendanceQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/attendance/cost")
@Tag(name = "Resource Cost", description = "Resource cost calculator: monthlyRate x (presentDays / workingDays)")
public class ResourceCostController {

    private final AttendanceQueryService attendanceQueryService;

    public ResourceCostController(AttendanceQueryService attendanceQueryService) {
        this.attendanceQueryService = attendanceQueryService;
    }

    /**
     * Monthly cost dashboard: one summary per resource active on the project.
     * GET /api/attendance/cost/monthly?projectId=&year=&month=
     */
    @Operation(
            summary = "Monthly resource cost report (project dashboard)",
            description = "One MonthlyResourceCost per resource currently active on 'projectId', for the given "
                    + "month. cost = monthlyRate x (presentDays / workingDays), where monthlyRate is looked up "
                    + "from the resource's rate card using its current rateYear (e.g. \"Year-3\"). 0 when the "
                    + "resource has no rateYear set or no rate configured for it.")
    @GetMapping("/monthly")
    public List<MonthlyResourceCost> monthlyCostReport(
            @Parameter(description = "Project id") @RequestParam("projectId") String projectId,
            @Parameter(description = "Year", example = "2026") @RequestParam("year") int year,
            @Parameter(description = "Month (1-12)", example = "7") @RequestParam("month") int month) {
        return attendanceQueryService.monthlyCostReport(projectId, year, month);
    }

    /**
     * One resource's monthly cost.
     * GET /api/attendance/cost/employee?resourceId=&year=&month=
     */
    @Operation(summary = "One resource's monthly cost")
    @GetMapping("/employee")
    public MonthlyResourceCost employeeMonthlyCost(
            @Parameter(description = "res_id (Attendance ID)") @RequestParam("resourceId") String resourceId,
            @Parameter(description = "Year", example = "2026") @RequestParam("year") int year,
            @Parameter(description = "Month (1-12)", example = "7") @RequestParam("month") int month) {
        return attendanceQueryService.employeeMonthlyCost(resourceId, year, month);
    }

    /**
     * Activity cost: one summary per resource active during the activity, scoped to the activity's
     * execution window (fetched live from PMIS). Planned cost tracks monthlyRate x duration.
     * GET /api/attendance/cost/activity?projectId=&activityId=&resourceId=
     */
    @Operation(
            summary = "Activity resource cost report",
            description = "One ResourceCostSummary per resource active during the activity's start/end window. "
                    + "Per-day rate = monthlyRate / calendarDaysInMonth per month segment; unpaid-leave "
                    + "deductions and relaxation additions apply only within the uploaded periods. "
                    + "Pass resourceId to return only that resource's cost.")
    @GetMapping("/activity")
    public ResourceCostResult activityCostReport(
            @Parameter(description = "Project id") @RequestParam("projectId") String projectId,
            @Parameter(description = "Activity id from PMIS") @RequestParam("activityId") String activityId,
            @Parameter(description = "res_id to return a single resource's cost (optional)")
                    @RequestParam(value = "resourceId", required = false) String resourceId) {
        return attendanceQueryService.activityCostReport(projectId, activityId, resourceId);
    }

    /**
     * Yearly cost: project dashboard (one summary per active resource), summed per month.
     * GET /api/attendance/cost/yearly?projectId=&year=
     */
    @Operation(
            summary = "Yearly resource cost report",
            description = "One summary per resource active during the year on 'projectId'. Each summary's "
                    + "totalCost is the sum of its months, computed separately per month rather than blended.")
    @GetMapping("/yearly")
    public ResourceCostResult yearlyCostReport(
            @Parameter(description = "Project id") @RequestParam("projectId") String projectId,
            @Parameter(description = "Year", example = "2026") @RequestParam("year") int year) {
        return attendanceQueryService.yearlyCostReport(projectId, year);
    }

    /**
     * Cost report scoped to an activity upload period (arbitrary date range).
     * Per-day rate is computed per-month-segment so cross-month periods are priced correctly.
     * GET /api/attendance/cost/period?projectId=&startDate=&endDate=
     */
    @Operation(
            summary = "Resource cost report for an activity upload period",
            description = "Returns one ResourceCostSummary per resource active during the given date range. "
                    + "Per-day rate = monthlyRate / calendarDaysInMonth for each month segment within the period. "
                    + "Unpaid leave is computed using the cumulative quarter balance from prior uploads.")
    @GetMapping("/period")
    public ResourceCostResult periodCostReport(
            @Parameter(description = "Project id") @RequestParam("projectId") String projectId,
            @Parameter(description = "Organisation id filter") @RequestParam(required = false) String organisationId,
            @Parameter(description = "Period start date", example = "2026-01-09")
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Period end date", example = "2026-02-08")
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return attendanceQueryService.periodCostReport(projectId, organisationId, startDate, endDate);
    }
}
