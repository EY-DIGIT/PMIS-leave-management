package com.example.leavemanagement.service;

/*
import com.example.leavemanagement.dto.HolidayItem;
import com.example.leavemanagement.dto.LeaveApplyRequest;
import com.example.leavemanagement.dto.LeaveDayBreakdown;
import com.example.leavemanagement.dto.LeaveResponse;
import com.example.leavemanagement.dto.LeaveUpdateRequest;
import com.example.leavemanagement.entity.LeaveRequest;
import com.example.leavemanagement.entity.LeaveStatus;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaveService {

    private final LeaveRequestRepository leaveRepository;
    private final PublicHolidayRepository holidayRepository;

    public LeaveService(LeaveRequestRepository leaveRepository, PublicHolidayRepository holidayRepository) {
        this.leaveRepository = leaveRepository;
        this.holidayRepository = holidayRepository;
    }

    // Applies for leave; the chargeable working days are computed and stored.
    @Transactional
    public LeaveResponse applyForLeave(LeaveApplyRequest request) {
        if (request.endDate().isBefore(request.startDate())) {
            throw new BadRequestException("endDate must not be before startDate");
        }

        LeaveDayBreakdown breakdown = computeBreakdown(request.startDate(), request.endDate());
        LeaveRequest saved = leaveRepository.save(new LeaveRequest(
                request.employeeName(),
                request.email(),
                request.startDate(),
                request.endDate(),
                request.reason(),
                breakdown.workingDays()));
        return LeaveResponse.from(saved, breakdown);
    }

    @Transactional(readOnly = true)
    public LeaveResponse getLeave(Long id) {
        LeaveRequest req = findOrThrow(id);
        return LeaveResponse.from(req, computeBreakdown(req.getStartDate(), req.getEndDate()));
    }

    // Lists leave requests, optionally filtered by employee name.
    @Transactional(readOnly = true)
    public List<LeaveResponse> listLeaves(String employeeName) {
        List<LeaveRequest> requests = (employeeName == null || employeeName.isBlank())
                ? leaveRepository.findAll()
                : leaveRepository.findByEmployeeNameIgnoreCaseOrderByStartDateAsc(employeeName);
        return requests.stream()
                .map(req -> LeaveResponse.from(req, computeBreakdown(req.getStartDate(), req.getEndDate())))
                .toList();
    }

    // Updates employee details, dates and reason of an existing leave request.
    @Transactional
    public LeaveResponse updateLeave(Long id, LeaveUpdateRequest request) {
        if (request.endDate().isBefore(request.startDate())) {
            throw new BadRequestException("endDate must not be before startDate");
        }
        LeaveRequest req = findOrThrow(id);
        req.setEmployeeName(request.employeeName());
        req.setEmail(request.email());
        req.setStartDate(request.startDate());
        req.setEndDate(request.endDate());
        req.setReason(request.reason());
        LeaveDayBreakdown breakdown = computeBreakdown(request.startDate(), request.endDate());
        req.setWorkingDays(breakdown.workingDays());
        return LeaveResponse.from(req, breakdown);
    }

    // Moves a request to APPROVED / REJECTED / CANCELLED.
    @Transactional
    public LeaveResponse updateStatus(Long id, LeaveStatus status) {
        LeaveRequest req = findOrThrow(id);
        req.setStatus(status);
        return LeaveResponse.from(req, computeBreakdown(req.getStartDate(), req.getEndDate()));
    }

    private LeaveRequest findOrThrow(Long id) {
        return leaveRepository.findById(id).orElseThrow(() -> new NotFoundException("No leave request with id " + id));
    }

    // Splits the inclusive date range into working days, weekend days and
    // public-holiday days. A holiday that falls on a weekend is counted as a
    // weekend day only, so the three buckets always sum to the calendar days.
    private LeaveDayBreakdown computeBreakdown(LocalDate start, LocalDate end) {
        // A date may carry several holidays; join their names so the breakdown
        // shows all of them while the day is still counted once.
        Map<LocalDate, String> holidays =
                holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(start, end).stream()
                        .collect(Collectors.toMap(
                                PublicHoliday::getHolidayDate,
                                PublicHoliday::getName,
                                (a, b) -> a + ", " + b,
                                java.util.LinkedHashMap::new));

        int total = 0;
        int weekend = 0;
        int holidayDays = 0;
        int working = 0;
        var holidaysInRange = new java.util.ArrayList<HolidayItem>();

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            total++;
            DayOfWeek dow = day.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                weekend++;
            } else if (holidays.containsKey(day)) {
                holidayDays++;
                holidaysInRange.add(new HolidayItem(day, holidays.get(day)));
            } else {
                working++;
            }
        }

        return new LeaveDayBreakdown(total, working, weekend, holidayDays, List.copyOf(holidaysInRange));
    }
}
*/
