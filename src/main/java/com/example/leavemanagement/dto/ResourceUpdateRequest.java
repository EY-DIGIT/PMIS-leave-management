package com.example.leavemanagement.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Map;

/** Request body for updating an existing master resource. */
public record ResourceUpdateRequest(
        @NotBlank(message = "name is required") String name,
        @Email(message = "emailId must be a valid email address") String emailId,
        String designationType,
        String location,
        Map<String, Double> rateCardByYear,
        String category,
        String categoryDetails,
        @NotNull(message = "dateOfJoining is required") LocalDate dateOfJoining,
        LocalDate lastDate,
        @NotNull(message = "active is required") Boolean active,
        String projectId) {}
