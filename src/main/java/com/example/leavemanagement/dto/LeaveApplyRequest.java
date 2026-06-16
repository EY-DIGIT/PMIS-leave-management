package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Request body for applying for leave. */
public record LeaveApplyRequest(
        @NotBlank(message = "employeeName is required") String employeeName,
        @NotNull(message = "startDate is required") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
        @NotNull(message = "endDate is required") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate endDate,
        String reason) {}
