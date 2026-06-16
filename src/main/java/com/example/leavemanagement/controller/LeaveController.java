package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.LeaveApplyRequest;
import com.example.leavemanagement.dto.LeaveResponse;
import com.example.leavemanagement.entity.LeaveStatus;
import com.example.leavemanagement.service.AttendanceLeaveService;
import com.example.leavemanagement.service.LeaveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/leaves")
@Tag(name = "Leaves", description = "Apply for leave and manage leave requests")
public class LeaveController {

    private final LeaveService leaveService;
    private final AttendanceLeaveService attendanceLeaveService;

    public LeaveController(LeaveService leaveService, AttendanceLeaveService attendanceLeaveService) {
        this.leaveService = leaveService;
        this.attendanceLeaveService = attendanceLeaveService;
    }

    /**
     * Apply for leave. The response includes the chargeable working days with
     * weekends and public holidays excluded. POST /api/leaves
     */
    @Operation(
            summary = "Apply for leave",
            description = "Computes chargeable working days by excluding Saturdays, Sundays and public "
                    + "holidays. Returns 201 with the day breakdown. Status starts as PENDING.")
    @PostMapping
    public ResponseEntity<LeaveResponse> apply(@Valid @RequestBody LeaveApplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leaveService.applyForLeave(request));
    }

    /**
     * Import leaves from a monthly attendance sheet. Each day where an employee's
     * In-Time and Out-Time are both 0 is treated as an absence; absent working
     * days (excluding weekends and public holidays) are grouped into consecutive
     * leave ranges. POST /api/leaves/from-attendance (multipart/form-data)
     */
    @Operation(
            summary = "Import leaves from an attendance sheet (Excel)",
            description = "Upload the monthly attendance .xlsx/.xls (3 rows per employee: In-Time/Out-Time/"
                    + "Total-Time, day columns 1..31). Days with In=0 and Out=0 become absences; absent "
                    + "working days are grouped into consecutive PENDING leaves. Weekends and public "
                    + "holidays are skipped. Employee names are resolved from the external directory by "
                    + "attendance id. Returns 201 with the created leaves.")
    @PostMapping(value = "/from-attendance", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<LeaveResponse>> importFromAttendance(
            @Parameter(description = "Month the sheet covers (1-12)", example = "6") @RequestParam("month") int month,
            @Parameter(description = "Year the sheet covers", example = "2026") @RequestParam("year") int year,
            @Parameter(description = "Attendance Excel file (.xlsx/.xls)") @RequestPart("file") MultipartFile file) {
        List<LeaveResponse> created = attendanceLeaveService.importLeaves(year, month, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** List leave requests, optionally filtered by ?employee=Name. */
    @Operation(summary = "List leave requests", description = "Optionally filter by employee name.")
    @GetMapping
    public List<LeaveResponse> list(
            @Parameter(description = "Filter by employee name", example = "Asha")
                    @RequestParam(name = "employee", required = false) String employee) {
        return leaveService.listLeaves(employee);
    }

    /** Get a single leave request. GET /api/leaves/{id} */
    @Operation(summary = "Get a single leave request", description = "Returns 404 if not found.")
    @GetMapping("/{id}")
    public LeaveResponse get(@PathVariable Long id) {
        return leaveService.getLeave(id);
    }

    /** Update status. PATCH /api/leaves/{id}/status?value=APPROVED */
    @Operation(
            summary = "Update leave status",
            description = "Set the status to PENDING, APPROVED, REJECTED or CANCELLED.")
    @PatchMapping("/{id}/status")
    public LeaveResponse updateStatus(
            @PathVariable Long id,
            @Parameter(description = "New status", example = "APPROVED") @RequestParam("value") LeaveStatus value) {
        return leaveService.updateStatus(id, value);
    }
}
