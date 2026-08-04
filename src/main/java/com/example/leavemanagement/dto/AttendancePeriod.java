package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;

public record AttendancePeriod(
        String label,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate startDate,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate endDate) {}
