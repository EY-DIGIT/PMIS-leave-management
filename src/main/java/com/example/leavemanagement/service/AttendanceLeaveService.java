package com.example.leavemanagement.service;

import com.example.leavemanagement.client.EmployeeDirectoryClient;
import com.example.leavemanagement.client.EmployeeInfo;
import com.example.leavemanagement.dto.EmployeeAttendance;
import com.example.leavemanagement.dto.EmployeeAttendanceSummary;
import com.example.leavemanagement.dto.HolidayItem;
import com.example.leavemanagement.dto.MonthlyAttendanceSummary;
import com.example.leavemanagement.dto.WeekendDates;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.repository.ProjectConfigRepository;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import com.example.leavemanagement.repository.ResourceProjectMappingRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Builds a monthly attendance summary: Saturdays/Sundays/public holidays, plus, per employee,
 * how many working days were marked absent (In-Time and Out-Time both 0) and how many worked
 * days ran short of a full day. The employee name is resolved from the external directory by
 * attendance id.
 */
@Service
public class AttendanceLeaveService {

    private static final int FULL_DAY_MINUTES = 8 * 60; // a day under this counts as a "short" day
    private static final int HALF_DAY_MINUTES = 4 * 60; // a day at/under this is a "half day"
    private static final DateTimeFormatter DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH);

    private final AttendanceExcelParser parser;
    private final EmployeeDirectoryClient directory;
    private final PublicHolidayRepository holidayRepository;
    private final ResourceProjectMappingRepository resourceProjectMappingRepository;
    private final ProjectConfigRepository projectConfigRepository;

    public AttendanceLeaveService(
            AttendanceExcelParser parser,
            EmployeeDirectoryClient directory,
            PublicHolidayRepository holidayRepository,
            ResourceProjectMappingRepository resourceProjectMappingRepository,
            ProjectConfigRepository projectConfigRepository) {
        this.parser = parser;
        this.directory = directory;
        this.holidayRepository = holidayRepository;
        this.resourceProjectMappingRepository = resourceProjectMappingRepository;
        this.projectConfigRepository = projectConfigRepository;
    }

    /**
     * Summarises one month from an attendance sheet without persisting anything:
     * the count of Saturdays, Sundays and public holidays, plus, per employee, how
     * many leaves were taken and how many worked days were under 8 hours.
     */
    @Transactional(readOnly = true)
    public MonthlyAttendanceSummary summarize(int year, int month, MultipartFile file) {
        validateMonthAndYear(year, month);
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        List<EmployeeAttendance> attendance = parser.parse(file, monthStart, monthEnd).stream()
                .map(e -> new EmployeeAttendance(
                        e.attendanceId(),
                        e.employeeName(),
                        e.designation(),
                        e.absentDates().stream().map(java.time.LocalDate::getDayOfMonth)
                                .collect(java.util.stream.Collectors.toSet()),
                        e.workedMinutesByDate().entrySet().stream()
                                .collect(java.util.stream.Collectors.toMap(
                                        en -> en.getKey().getDayOfMonth(), Map.Entry::getValue))))
                .toList();
        return buildSummary(year, month, attendance);
    }

    /**
     * Builds the monthly summary from already-parsed (or stored) per-employee
     * attendance. Shared by the file upload and the stored-data GET endpoint.
     */
    @Transactional(readOnly = true)
    public MonthlyAttendanceSummary buildSummary(int year, int month, List<EmployeeAttendance> attendance) {
        validateMonthAndYear(year, month);

        LocalDate monthStart = LocalDate.of(year, month, 1);
        int lengthOfMonth = monthStart.lengthOfMonth();
        LocalDate monthEnd = monthStart.withDayOfMonth(lengthOfMonth);

        List<PublicHoliday> holidayEntities =
                holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(monthStart, monthEnd);
        Set<LocalDate> holidayDates =
                holidayEntities.stream().map(PublicHoliday::getHolidayDate).collect(Collectors.toSet());
        List<HolidayItem> publicHolidays = holidayEntities.stream()
                .map(h -> new HolidayItem(h.getHolidayDate(), h.getName()))
                .toList();

        List<LocalDate> saturdayDates = new ArrayList<>();
        List<LocalDate> sundayDates = new ArrayList<>();
        for (int day = 1; day <= lengthOfMonth; day++) {
            LocalDate date = LocalDate.of(year, month, day);
            DayOfWeek dow = date.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY) {
                saturdayDates.add(date);
            } else if (dow == DayOfWeek.SUNDAY) {
                sundayDates.add(date);
            }
        }
        int saturdays = saturdayDates.size();
        int sundays = sundayDates.size();

        List<EmployeeAttendanceSummary> employees = new ArrayList<>();
        for (EmployeeAttendance employee : attendance) {
            int[] thresholds = getThresholds(employee.attendanceId());
            int fullDayMinutes = thresholds[0];
            int halfDayMinutes = thresholds[1];

            int leaveDays = (int) employee.absentDays().stream()
                    .filter(day -> day >= 1 && day <= lengthOfMonth)
                    .map(day -> LocalDate.of(year, month, day))
                    .filter(date -> isWorkingDay(date, holidayDates))
                    .count();

            Map<String, String> shortHours = new LinkedHashMap<>();
            List<String> halfDays = new ArrayList<>();
            final int fullDay = fullDayMinutes;
            final int halfDay = halfDayMinutes;
            employee.workedMinutesByDay().entrySet().stream()
                    .filter(e -> e.getValue() < fullDay)
                    .filter(e -> e.getKey() >= 1 && e.getKey() <= lengthOfMonth)
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        String date = LocalDate.of(year, month, e.getKey()).format(DAY_MONTH_YEAR);
                        if (e.getValue() <= halfDay) {
                            halfDays.add(date);
                        } else {
                            shortHours.put(date, formatHours(fullDay - e.getValue()));
                        }
                    });

            employees.add(new EmployeeAttendanceSummary(
                    employee.attendanceId(),
                    resolveName(employee),
                    employee.designation(),
                    leaveDays,
                    shortHours.size(),
                    shortHours,
                    halfDays));
        }

        return new MonthlyAttendanceSummary(
                year,
                month,
                lengthOfMonth,
                saturdays,
                sundays,
                saturdays + sundays,
                List.of(new WeekendDates(List.copyOf(saturdayDates), List.copyOf(sundayDates))),
                publicHolidays.size(),
                publicHolidays,
                employees.size(),
                employees);
    }

    private int[] getThresholds(String attendanceId) {
        return resourceProjectMappingRepository.findById(attendanceId)
                .map(m -> projectConfigRepository.findById(m.getProjectId()).orElse(null))
                .map(c -> new int[]{c.getFullDayMinutes(), c.getHalfDayMinutes()})
                .orElse(new int[]{FULL_DAY_MINUTES, HALF_DAY_MINUTES});
    }

    /** Formats worked minutes as a friendly "X hrs" string (e.g. 150 -> "2.5 hrs", 420 -> "7 hrs"). */
    private String formatHours(int minutes) {
        double hours = minutes / 60.0;
        String value = hours == Math.floor(hours)
                ? Integer.toString((int) hours)
                : Double.toString(Math.round(hours * 100.0) / 100.0);
        return value + " hrs";
    }

    private void validateMonthAndYear(int year, int month) {
        if (month < 1 || month > 12) {
            throw new BadRequestException("month must be between 1 and 12");
        }
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }
    }

    private String resolveName(EmployeeAttendance employee) {
        return directory
                .findByAttendanceId(employee.attendanceId())
                .map(EmployeeInfo::employeeName)
                .filter(name -> !name.isBlank())
                .orElseGet(() -> employee.employeeName().isBlank()
                        ? "Attendance " + employee.attendanceId()
                        : employee.employeeName());
    }

    private boolean isWorkingDay(LocalDate date, Set<LocalDate> holidays) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(date);
    }
}
