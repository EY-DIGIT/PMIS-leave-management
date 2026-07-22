package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.MonthlyResourceCost;
import com.example.leavemanagement.dto.ResourceCostResult;
import java.util.List;
import com.example.leavemanagement.service.AttendanceQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
     * Quarterly cost: one resource (resourceId) or the whole project dashboard (projectId). Each
     * resource's total is the sum of its 3 months, computed separately per month.
     * GET /api/attendance/cost/quarterly?projectId=&resourceId=&year=&quarter=
     */
    @Operation(
            summary = "Quarterly resource cost report",
            description = "Pass resourceId for one resource's cost summary, or projectId for the project "
                    + "dashboard (one summary per active resource). Each summary's totalCost is the sum of its "
                    + "3 months, computed separately per month rather than blended over the quarter. Calendar "
                    + "quarters: Q1 Jan-Mar, Q2 Apr-Jun, Q3 Jul-Sep, Q4 Oct-Dec.")
    @GetMapping("/quarterly")
    public ResourceCostResult quarterlyCostReport(
            @Parameter(description = "Project id (dashboard mode)") @RequestParam(required = false)
                    String projectId,
            @Parameter(description = "res_id (single-resource mode)") @RequestParam(required = false)
                    String resourceId,
            @Parameter(description = "Year", example = "2026") @RequestParam("year") int year,
            @Parameter(description = "Quarter (1-4)", example = "3") @RequestParam("quarter") int quarter) {
        return attendanceQueryService.quarterlyCostReport(projectId, resourceId, year, quarter);
    }

    /**
     * Yearly cost: one resource (resourceId) or the whole project dashboard (projectId). Each
     * resource's total is the sum of its 12 months, computed separately per month.
     * GET /api/attendance/cost/yearly?projectId=&resourceId=&year=
     */
    @Operation(
            summary = "Yearly resource cost report",
            description = "Pass resourceId for one resource's cost summary, or projectId for the project "
                    + "dashboard (one summary per active resource). Each summary's totalCost is the sum of its "
                    + "12 months, computed separately per month rather than blended over the year.")
    @GetMapping("/yearly")
    public ResourceCostResult yearlyCostReport(
            @Parameter(description = "Project id (dashboard mode)") @RequestParam(required = false)
                    String projectId,
            @Parameter(description = "res_id (single-resource mode)") @RequestParam(required = false)
                    String resourceId,
            @Parameter(description = "Year", example = "2026") @RequestParam("year") int year) {
        return attendanceQueryService.yearlyCostReport(projectId, resourceId, year);
    }
}
