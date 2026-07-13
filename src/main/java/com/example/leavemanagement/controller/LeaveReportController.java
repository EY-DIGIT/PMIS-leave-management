package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.EmployeeLeaveDetail;
import com.example.leavemanagement.dto.LeaveReportSummary;
import com.example.leavemanagement.service.LeaveReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class LeaveReportController {

    private final LeaveReportService leaveReportService;

    public LeaveReportController(LeaveReportService leaveReportService) {
        this.leaveReportService = leaveReportService;
    }

    @GetMapping("/leave")
    public LeaveReportSummary quarterlySummary(
            @RequestParam("year") int year,
            @RequestParam("quarter") int quarter,
            @RequestParam(required = false) String projectId) {
        return leaveReportService.quarterlySummary(year, quarter, projectId);
    }

    @GetMapping("/leave/{attendanceId}")
    public EmployeeLeaveDetail employeeDetail(
            @PathVariable String attendanceId,
            @RequestParam("year") int year,
            @RequestParam("quarter") int quarter,
            @RequestParam(required = false) String projectId) {
        return leaveReportService.employeeDetail(attendanceId, year, quarter, projectId);
    }
}
