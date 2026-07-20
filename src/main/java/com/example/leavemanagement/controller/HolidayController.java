package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.CalendarSummary;
import com.example.leavemanagement.dto.HolidayItem;
import com.example.leavemanagement.dto.HolidayUploadRequest;
import com.example.leavemanagement.service.FileStorageService;
import com.example.leavemanagement.service.HolidayExcelParser;
import com.example.leavemanagement.service.HolidayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@Tag(name = "Holidays & Calendar", description = "Upload public holidays and read per-year calendar data")
public class HolidayController {

    private static final Logger log = LoggerFactory.getLogger(HolidayController.class);

    private final HolidayService holidayService;
    private final HolidayExcelParser excelParser;
    private final FileStorageService fileStorageService;

    public HolidayController(
            HolidayService holidayService,
            HolidayExcelParser excelParser,
            FileStorageService fileStorageService) {
        this.holidayService = holidayService;
        this.excelParser = excelParser;
        this.fileStorageService = fileStorageService;
    }

    /**
     * API 1 — Upload public holidays for a year. The dates are marked onto the
     * calendar for that year (1-Jan to 31-Dec).
     *
     * <p>POST /api/holidays
     */
    @Operation(
            summary = "Upload public holidays for a year (JSON)",
            description = "Marks the given dates onto the year's calendar. A date may carry more than one "
                    + "holiday. Every date must fall inside the year (else 400). "
                    + "Returns 201 with the full sorted holiday list for the year.")
    @PostMapping(value = "/holidays", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<HolidayItem>> uploadHolidays(@Valid @RequestBody HolidayUploadRequest request) {
        List<HolidayItem> saved = holidayService.uploadHolidays(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * API 1 (variant) — Upload public holidays for a year from an Excel file.
     *
     * <p>POST /api/holidays (multipart/form-data)
     */
    @Operation(
            summary = "Upload public holidays for a year (Excel file)",
            description = "Upload an .xlsx/.xls file whose first sheet has a header row followed by rows of "
                    + "[S.No, Holiday, Date, Day] — column A = serial number (ignored), column B = holiday "
                    + "name, column C = the date without a year (e.g. \"26 January\"; resolved against "
                    + "'year'), column D = day of week (e.g. \"Monday\", optional — cross-checked against the "
                    + "actual day of week for column C's date if given). Every date must fall inside 'year' "
                    + "(else 400). Returns 201 with the full sorted holiday list for the year.")
    @PostMapping(value = "/holidays", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<HolidayItem>> uploadHolidaysFromExcel(
            @Parameter(description = "Year the holidays belong to", example = "2026") @RequestParam("year") int year,
            @Parameter(description = "Excel file (.xlsx/.xls) with [S.No, Holiday, Date, Day] rows")
                    @RequestPart("file") MultipartFile file) {
        List<HolidayItem> items = excelParser.parse(file, year);
        List<HolidayItem> saved = holidayService.uploadHolidays(new HolidayUploadRequest(year, items));
        try {
            fileStorageService.save(file, "holidays");
        } catch (Exception e) {
            log.warn("Holiday file could not be saved to storage (NFS may be unavailable): {}", e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * API 2 — Get calendar data for a year: number of Saturdays and Sundays
     * plus the public holidays with their dates.
     *
     * <p>GET /api/calendar/{year}
     */
    @Operation(
            summary = "Get calendar data for a year (optionally a single month)",
            description = "Returns the count of Saturdays and Sundays plus the public holidays. "
                    + "Pass month=1..12 for one month; month=all (the default) returns the year totals "
                    + "with a per-month breakdown in 'months'.")
    @GetMapping("/calendar/{year}")
    public CalendarSummary getCalendar(
            @Parameter(description = "Four-digit year, e.g. 2026", example = "2026") @PathVariable int year,
            @Parameter(description = "Month 1-12, or 'all' for the whole year", example = "all")
                    @RequestParam(name = "month", required = false, defaultValue = "all") String month) {
        return holidayService.getCalendar(year, month);
    }

    /** Lists the public holidays for a year, optionally a single month. GET /api/holidays/{year} */
    @Operation(
            summary = "List public holidays for a year (optionally a single month)",
            description = "Pass month=1..12 to filter to one month; month=all (the default) returns the year.")
    @GetMapping("/holidays/{year}")
    public List<HolidayItem> listHolidays(
            @Parameter(description = "Four-digit year, e.g. 2026", example = "2026") @PathVariable int year,
            @Parameter(description = "Month 1-12, or 'all' for the whole year", example = "all")
                    @RequestParam(name = "month", required = false, defaultValue = "all") String month) {
        return holidayService.listHolidays(year, month);
    }

    /** Removes a public holiday by date. DELETE /api/holidays/2026-01-26 */
    @Operation(
            summary = "Delete public holiday(s) by date",
            description = "Removes every holiday on the date, or just the one matching the optional "
                    + "'name' query parameter. Returns 404 if no matching holiday exists.")
    @DeleteMapping("/holidays/{date}")
    public ResponseEntity<Void> deleteHoliday(
            @Parameter(description = "ISO date, e.g. 2026-01-26", example = "2026-01-26")
                    @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "Optional — delete only this named holiday on the date", example = "Republic Day")
                    @RequestParam(name = "name", required = false) String name) {
        holidayService.deleteHoliday(date, name);
        return ResponseEntity.noContent().build();
    }
}
