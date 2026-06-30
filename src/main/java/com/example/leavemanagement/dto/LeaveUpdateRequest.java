package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Request body for updating an existing leave request. */
public record LeaveUpdateRequest(
        @NotBlank(message = "employeeName is required") String employeeName,
        @Email(message = "email must be a valid email address") String email,
        @NotNull(message = "startDate is required") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
        @NotNull(message = "endDate is required") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate endDate,
        String reason) {}
