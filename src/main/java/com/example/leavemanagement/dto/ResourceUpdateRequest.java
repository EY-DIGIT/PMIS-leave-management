package com.example.leavemanagement.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;

/** Request body for updating an existing master resource. */
public record ResourceUpdateRequest(
        @NotBlank(message = "name is required") String name,
        @Email(message = "emailId must be a valid email address") String emailId,
        @PositiveOrZero(message = "rateCard must not be negative") Double rateCard,
        @NotNull(message = "dateOfJoining is required") LocalDate dateOfJoining,
        LocalDate lastDate,
        String designationType,
        @NotNull(message = "active is required") Boolean active,
        String projectId) {}
