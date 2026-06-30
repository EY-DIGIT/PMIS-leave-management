package com.example.leavemanagement.service;

import com.example.leavemanagement.client.EmployeeDirectoryClient;
import com.example.leavemanagement.client.EmployeeInfo;
import com.example.leavemanagement.dto.AttendanceSummaryReport;
import com.example.leavemanagement.dto.EmployeeAttendance;
import com.example.leavemanagement.dto.MonthlyAttendanceStored;
import com.example.leavemanagement.dto.MonthlyAttendanceSummary;
import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import com.example.leavemanagement.dto.QuarterLeaveReport;
import com.example.leavemanagement.dto.ResourceQuarterSettlement;
import com.example.leavemanagement.entity.ProjectConfig;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.entity.ResourceMonthlyAttendance;
import com.example.leavemanagement.entity.ResourceProjectMapping;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.repository.ProjectConfigRepository;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import com.example.leavemanagement.repository.ResourceMonthlyAttendanceRepository;
import com.example.leavemanagement.repository.ResourceProjectMappingRepository;
import java.util.Optional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Persists monthly attendance and answers questions over the stored data:
 * a monthly summary (weekends/holidays/leaves/short-hour days) and the quarterly
 * leave-policy settlement (UIDAI 5.24.1).
 */
@Service
public class AttendanceQueryService {

    private final AttendanceExcelParser parser;
    private final ResourceMonthlyAttendanceRepository attendanceRepository;
    private final PublicHolidayRepository holidayRepository;
    private final EmployeeDirectoryClient directory;
    private final QuarterLeavePolicy policy;
    private final AttendanceLeaveService attendanceLeaveService;
    private final ResourceProjectMappingRepository resourceProjectMappingRepository;
    private final ProjectConfigRepository projectConfigRepository;

    public AttendanceQueryService(
            AttendanceExcelParser parser,
            ResourceMonthlyAttendanceRepository attendanceRepository,
            PublicHolidayRepository holidayRepository,
            EmployeeDirectoryClient directory,
            QuarterLeavePolicy policy,
            AttendanceLeaveService attendanceLeaveService,
            ResourceProjectMappingRepository resourceProjectMappingRepository,
            ProjectConfigRepository projectConfigRepository) {
        this.parser = parser;
        this.attendanceRepository = attendanceRepository;
        this.holidayRepository = holidayRepository;
        this.directory = directory;
        this.policy = policy;
        this.attendanceLeaveService = attendanceLeaveService;
        this.resourceProjectMappingRepository = resourceProjectMappingRepository;
        this.projectConfigRepository = projectConfigRepository;
    }

    /**
     * Parses and upserts a monthly attendance sheet so it can be queried later.
     * Only absent weekdays are kept; worked minutes per day are stored for the
     * short-hours calculation. Re-uploading a month overwrites it.
     */
    @Transactional
    public MonthlyAttendanceStored storeMonthly(int year, int month, MultipartFile file) {
        validateMonthAndYear(year, month);
        int stored = persist(year, month, parser.parse(file));
        return new MonthlyAttendanceStored(year, month, stored);
    }

    /**
     * Stores the month's attendance and returns its summary in one call — used by
     * the upload endpoint so the data is immediately available to the GET summary
     * and the quarterly settlement.
     */
    @Transactional
    public MonthlyAttendanceSummary storeAndSummarize(int year, int month, MultipartFile file) {
        validateMonthAndYear(year, month);
        List<EmployeeAttendance> parsed = parser.parse(file);
        persist(year, month, parsed);
        return attendanceLeaveService.buildSummary(year, month, parsed);
    }

    /** Replaces the month's stored rows with the freshly parsed set (handles repeated/masked ids). */
    private int persist(int year, int month, List<EmployeeAttendance> employees) {
        List<ResourceMonthlyAttendance> existing = attendanceRepository.findByYearAndMonth(year, month);
        if (!existing.isEmpty()) {
            attendanceRepository.deleteAll(existing);
            attendanceRepository.flush(); // apply deletes before inserting the replacements
        }
        int stored = 0;
        for (EmployeeAttendance employee : employees) {
            ResourceMonthlyAttendance row = new ResourceMonthlyAttendance(
                    employee.attendanceId(), employee.employeeName(), employee.designation(), year, month);
            row.setAbsentDays(absentWeekdays(year, month, employee.absentDays()));
            row.setWorkedMinutesByDay(new LinkedHashMap<>(employee.workedMinutesByDay()));
            attendanceRepository.save(row);
            stored++;
        }
        return stored;
    }

    /**
     * Summary from stored attendance (no file upload). Returns a flat
     * {@link MonthlyAttendanceSummary} for a single month ({@code month}=1-12), or an
     * {@link AttendanceSummaryReport} (one summary per stored month) for
     * {@code month}=null/blank/"all".
     */
    @Transactional(readOnly = true)
    public Object summary(int year, String month) {
        Integer monthValue = parseMonthParam(month);
        return monthValue != null ? monthlySummary(year, monthValue) : allMonthsSummary(year);
    }

    /** Flat summary for one stored month. */
    @Transactional(readOnly = true)
    public MonthlyAttendanceSummary monthlySummary(int year, int month) {
        validateMonthAndYear(year, month);
        return summaryFromRows(year, month, attendanceRepository.findByYearAndMonth(year, month));
    }

    /** One summary per stored month of the year, sorted ascending. */
    @Transactional(readOnly = true)
    public AttendanceSummaryReport allMonthsSummary(int year) {
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }
        Map<Integer, List<ResourceMonthlyAttendance>> byMonth = attendanceRepository.findByYear(year).stream()
                .collect(Collectors.groupingBy(ResourceMonthlyAttendance::getMonth));
        List<MonthlyAttendanceSummary> months = byMonth.keySet().stream()
                .sorted()
                .map(m -> summaryFromRows(year, m, byMonth.get(m)))
                .toList();
        return new AttendanceSummaryReport(year, null, months);
    }

    private MonthlyAttendanceSummary summaryFromRows(int year, int month, List<ResourceMonthlyAttendance> rows) {
        List<EmployeeAttendance> attendance = rows.stream()
                .map(row -> new EmployeeAttendance(
                        row.getAttendanceId(),
                        row.getEmployeeName(),
                        row.getDesignation(),
                        row.getAbsentDays(),
                        row.getWorkedMinutesByDay()))
                .toList();
        return attendanceLeaveService.buildSummary(year, month, attendance);
    }

    /** Parses the month parameter: {@code null}/blank/"all" -> null (all months), else 1-12. */
    private Integer parseMonthParam(String month) {
        if (month == null || month.isBlank() || month.equalsIgnoreCase("all")) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(month.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("month must be 1-12 or 'all'");
        }
        if (value < 1 || value > 12) {
            throw new BadRequestException("month must be 1-12 or 'all'");
        }
        return value;
    }

    /**
     * Applies the quarterly leave policy (UIDAI 5.24.1) across the quarter's stored
     * attendance, one settlement per resource. Quarters are calendar quarters:
     * Q1 Jan-Mar, Q2 Apr-Jun, Q3 Jul-Sep, Q4 Oct-Dec.
     */
    @Transactional(readOnly = true)
    public QuarterLeaveReport quarterlySettlement(int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new BadRequestException("quarter must be between 1 and 4");
        }
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }

        List<Integer> months = List.of(quarter * 3 - 2, quarter * 3 - 1, quarter * 3);
        LocalDate quarterStart = LocalDate.of(year, months.get(0), 1);
        LocalDate quarterEnd = LocalDate.of(year, months.get(2), 1)
                .withDayOfMonth(LocalDate.of(year, months.get(2), 1).lengthOfMonth());

        Set<LocalDate> holidays =
                holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(quarterStart, quarterEnd).stream()
                        .map(PublicHoliday::getHolidayDate)
                        .collect(Collectors.toSet());

        List<ResourceMonthlyAttendance> rows = attendanceRepository.findByYearAndMonthIn(year, months);
        Set<Integer> monthsWithData =
                rows.stream().map(ResourceMonthlyAttendance::getMonth).collect(Collectors.toCollection(HashSet::new));

        // Group stored rows by resource.
        Map<String, List<ResourceMonthlyAttendance>> byResource =
                rows.stream().collect(Collectors.groupingBy(ResourceMonthlyAttendance::getAttendanceId));

        List<ResourceQuarterSettlement> settlements = new ArrayList<>();
        for (Map.Entry<String, List<ResourceMonthlyAttendance>> entry : byResource.entrySet()) {
            String attendanceId = entry.getKey();
            Set<LocalDate> absentDates = new HashSet<>();
            String sheetName = attendanceId;
            for (ResourceMonthlyAttendance row : entry.getValue()) {
                if (row.getEmployeeName() != null && !row.getEmployeeName().isBlank()) {
                    sheetName = row.getEmployeeName();
                }
                int lengthOfMonth = LocalDate.of(year, row.getMonth(), 1).lengthOfMonth();
                for (Integer day : row.getAbsentDays()) {
                    if (day >= 1 && day <= lengthOfMonth) {
                        absentDates.add(LocalDate.of(year, row.getMonth(), day));
                    }
                }
            }

            EmployeeInfo info = directory.findByAttendanceId(attendanceId).orElse(null);
            ResourceProjectMapping mapping =
                    resourceProjectMappingRepository.findById(attendanceId).orElse(null);

            String mappingName = mapping != null && mapping.getEmployeeName() != null
                    && !mapping.getEmployeeName().isBlank() ? mapping.getEmployeeName() : null;
            String infoName = info != null && info.employeeName() != null
                    && !info.employeeName().isBlank() ? info.employeeName() : null;
            String employeeName = mappingName != null ? mappingName
                    : (infoName != null ? infoName : sheetName);

            LocalDate mappingJoining = mapping != null ? mapping.getJoiningDate() : null;
            LocalDate joiningDate = mappingJoining != null ? mappingJoining
                    : (info != null ? info.joiningDate() : null);

            int maxLeaves = Optional.ofNullable(mapping)
                    .map(m -> projectConfigRepository.findById(m.getProjectId()).orElse(null))
                    .map(ProjectConfig::getMaxLeavesPerPeriod)
                    .orElse(QuarterLeavePolicy.MAX_PERMISSIBLE_LEAVE);

            QuarterLeaveCalculation calculation =
                    policy.compute(quarterStart, quarterEnd, joiningDate, absentDates, holidays, maxLeaves);

            settlements.add(new ResourceQuarterSettlement(attendanceId, employeeName, joiningDate, calculation));
        }
        settlements.sort(Comparator.comparing(ResourceQuarterSettlement::attendanceId));

        return new QuarterLeaveReport(
                year,
                quarter,
                quarterStart,
                quarterEnd,
                monthsWithData.stream().sorted().toList(),
                settlements.size(),
                settlements);
    }

    /** Absent day-numbers restricted to weekdays (weekend 0/0 cells carry no meaning). */
    private Set<Integer> absentWeekdays(int year, int month, Set<Integer> absentDays) {
        int lengthOfMonth = LocalDate.of(year, month, 1).lengthOfMonth();
        Set<Integer> weekdays = new HashSet<>();
        for (Integer day : absentDays) {
            if (day < 1 || day > lengthOfMonth) {
                continue;
            }
            DayOfWeek dow = LocalDate.of(year, month, day).getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                weekdays.add(day);
            }
        }
        return weekdays;
    }

    private void validateMonthAndYear(int year, int month) {
        if (month < 1 || month > 12) {
            throw new BadRequestException("month must be between 1 and 12");
        }
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }
    }
}
