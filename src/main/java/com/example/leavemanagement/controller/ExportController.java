package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.HolidayItem;
import com.example.leavemanagement.dto.ResourceResponse;
import com.example.leavemanagement.dto.StoredFileInfo;
import com.example.leavemanagement.service.FileStorageService;
import com.example.leavemanagement.service.HolidayExcelExporter;
import com.example.leavemanagement.service.HolidayService;
import com.example.leavemanagement.service.MasterResourceService;
import com.example.leavemanagement.service.ResourceExcelExporter;
import com.example.leavemanagement.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/export")
@Tag(name = "Export", description = "Download stored uploaded Excel files and export data as Excel from the database")
public class ExportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final FileStorageService fileStorageService;
    private final HolidayService holidayService;
    private final HolidayExcelExporter holidayExcelExporter;
    private final MasterResourceService masterResourceService;
    private final ResourceExcelExporter resourceExcelExporter;
    private final TemplateService templateService;

    public ExportController(
            FileStorageService fileStorageService,
            HolidayService holidayService,
            HolidayExcelExporter holidayExcelExporter,
            MasterResourceService masterResourceService,
            ResourceExcelExporter resourceExcelExporter,
            TemplateService templateService) {
        this.fileStorageService = fileStorageService;
        this.holidayService = holidayService;
        this.holidayExcelExporter = holidayExcelExporter;
        this.masterResourceService = masterResourceService;
        this.resourceExcelExporter = resourceExcelExporter;
        this.templateService = templateService;
    }

    // ------------------------------------------------------------------
    // List stored files
    // ------------------------------------------------------------------

    @Operation(
            summary = "List stored attendance Excel files",
            description = "Returns metadata (filename, periodStart, periodEnd, size, upload time) for stored "
                    + "attendance Excels. Narrow by milestoneId (optional) and/or a time window: "
                    + "year alone = full year, year+quarter = one quarter, year+month = one month. "
                    + "A file is included when its attendance period overlaps the requested window.")
    @GetMapping("/files/attendance")
    public List<StoredFileInfo> listAttendanceFiles(
            @Parameter(description = "Project id") @RequestParam("projectId") String projectId,
            @Parameter(description = "Milestone id (optional)") @RequestParam(required = false) String milestoneId,
            @Parameter(description = "Year filter, e.g. 2026") @RequestParam(required = false) Integer year,
            @Parameter(description = "Quarter filter 1-4 (requires year)") @RequestParam(required = false) Integer quarter,
            @Parameter(description = "Month filter 1-12 (requires year; overrides quarter)")
                    @RequestParam(required = false) Integer month) {
        return fileStorageService.listAttendance(projectId, milestoneId, year, quarter, month);
    }

    @Operation(summary = "List stored holiday Excel files")
    @GetMapping("/files/holidays")
    public List<StoredFileInfo> listHolidayFiles() {
        return fileStorageService.list("holidays");
    }

    @Operation(summary = "List stored resource master Excel files")
    @GetMapping("/files/resources")
    public List<StoredFileInfo> listResourceFiles(
            @Parameter(description = "Project id (optional — omit to list all projects)")
                    @RequestParam(required = false) String projectId) {
        String subDir = projectId != null && !projectId.isBlank()
                ? "resources/" + projectId
                : "resources";
        return fileStorageService.list(subDir);
    }

    // ------------------------------------------------------------------
    // Download a stored file
    // ------------------------------------------------------------------

    @Operation(
            summary = "Download a stored Excel file",
            description = "Pass the 'relativePath' value from the list endpoints (e.g. "
                    + "'attendance/P1/M1/20260701_120000_attendance.xlsx'). "
                    + "Returns the raw Excel file as a download.")
    @GetMapping("/file")
    public ResponseEntity<FileSystemResource> downloadFile(
            @Parameter(description = "Relative path as returned by the list endpoints")
                    @RequestParam("path") String relativePath) throws IOException {
        Path file = fileStorageService.resolve(relativePath);
        String filename = file.getFileName().toString();
        return ResponseEntity.ok()
                .contentType(XLSX)
                .contentLength(Files.size(file))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(new FileSystemResource(file));
    }

    // ------------------------------------------------------------------
    // Generate Excel from DB
    // ------------------------------------------------------------------

    @Operation(
            summary = "Export holidays list as Excel",
            description = "Generates and downloads a holiday Excel for the given year from the database. "
                    + "Format matches the import template: [S.No, Holiday Name, Date, Day].")
    @GetMapping("/holidays/{year}")
    public ResponseEntity<ByteArrayResource> exportHolidays(
            @Parameter(description = "Four-digit year, e.g. 2026", example = "2026") @PathVariable int year) {
        List<HolidayItem> holidays = holidayService.listHolidays(year, "all");
        byte[] bytes = holidayExcelExporter.export(year, holidays);
        String filename = "holidays_" + year + ".xlsx";
        return xlsxResponse(bytes, filename);
    }

    @Operation(
            summary = "Export resource list as Excel",
            description = "Generates and downloads a resource master Excel from the database. "
                    + "Pass projectId to restrict to one project. Format matches the import template.")
    @GetMapping("/resources")
    public ResponseEntity<ByteArrayResource> exportResources(
            @Parameter(description = "Project id (optional — omit for all projects)")
                    @RequestParam(required = false) String projectId) {
        List<ResourceResponse> resources =
                masterResourceService.search(null, null, null, null, projectId, null, null, null, null);
        byte[] bytes = resourceExcelExporter.export(projectId, resources);
        String filename = projectId != null && !projectId.isBlank()
                ? "resources_" + projectId + "_" + LocalDate.now() + ".xlsx"
                : "resources_" + LocalDate.now() + ".xlsx";
        return xlsxResponse(bytes, filename);
    }

    // ------------------------------------------------------------------
    // Blank upload templates
    // ------------------------------------------------------------------

    @Operation(
            summary = "Download blank attendance upload template",
            description = "Returns an empty .xlsx whose column layout exactly matches the attendance upload parser. "
                    + "Col A=Attendance ID, B=Employee Name, C=Designation, D=Type (In-Time/Out-Time/Total-Time), "
                    + "cols E onwards = day 1..N of the period. "
                    + "Pass the same startDate/endDate you will use when uploading — the template contains "
                    + "exactly that many day columns so the upload parser will accept it without error.")
    @GetMapping("/template/attendance")
    public ResponseEntity<ByteArrayResource> attendanceTemplate(
            @Parameter(description = "Period start date (yyyy-MM-dd)", example = "2026-07-01")
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Period end date (yyyy-MM-dd)", example = "2026-07-31")
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        byte[] bytes = templateService.attendanceTemplate(startDate, endDate);
        String filename = "attendance_template_" + startDate + "_" + endDate + ".xlsx";
        return xlsxResponse(bytes, filename);
    }

    @Operation(
            summary = "Download blank resource master upload template",
            description = "Returns an empty .xlsx whose column layout exactly matches the resource master upload "
                    + "parser: Attendance ID | Employee Name | Role as per Contract | Location | "
                    + "Date of Joining | Last Day of Working | Year-1..Year-7 (monthly rates) | "
                    + "Category (RFP/CCN/ASG) | CCN/ASG Details. "
                    + "Row 1 is a group-heading row (auto-skipped by the parser); data starts from row 3.")
    @GetMapping("/template/resources")
    public ResponseEntity<ByteArrayResource> resourceTemplate() {
        byte[] bytes = templateService.resourceTemplate();
        return xlsxResponse(bytes, "resource_master_template.xlsx");
    }

    @Operation(
            summary = "Download blank designation rate card upload template",
            description = "Returns an empty .xlsx whose column layout exactly matches the designation rate card "
                    + "upload parser: Role as per Contract | Year-1 Rate | … | Year-7 Rate. "
                    + "Fill in the role names and monthly rates, then POST to "
                    + "/api/designation-rates/upload before uploading the resource master.")
    @GetMapping("/template/designation-rates")
    public ResponseEntity<ByteArrayResource> designationRateTemplate() {
        byte[] bytes = templateService.designationRateTemplate();
        return xlsxResponse(bytes, "designation_rate_template.xlsx");
    }

    @Operation(
            summary = "Download blank holiday upload template",
            description = "Returns an empty .xlsx whose column layout exactly matches the holiday upload parser: "
                    + "S.No | Holiday | Date | Day. "
                    + "The Date column accepts 'dd MMMM' text (e.g. '26 January'), a full Excel date cell, "
                    + "or yyyy-MM-dd — all resolved against the year passed to the upload endpoint.")
    @GetMapping("/template/holidays")
    public ResponseEntity<ByteArrayResource> holidayTemplate(
            @Parameter(description = "Year the template is for, e.g. 2026", example = "2026")
                    @RequestParam(defaultValue = "2026") int year) {
        byte[] bytes = templateService.holidayTemplate(year);
        return xlsxResponse(bytes, "holidays_template_" + year + ".xlsx");
    }

    private ResponseEntity<ByteArrayResource> xlsxResponse(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .contentLength(bytes.length)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(new ByteArrayResource(bytes));
    }
}
