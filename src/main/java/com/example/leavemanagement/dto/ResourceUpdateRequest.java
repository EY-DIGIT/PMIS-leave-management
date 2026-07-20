package com.example.leavemanagement.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Map;

/**
 * Request body for updating an existing master resource. Updates master (employee) fields
 * unconditionally, plus the resource's current active assignment's role/rate card in place, if it
 * has one. Does not create, close, or move project assignments — use the Excel upload for that.
 *
 * <p>{@code active} toggles the resource's current assignment: {@code false} deactivates it and
 * requires {@code lastDate} (which becomes the assignment's end date / inactivation date);
 * {@code true} (or {@code null}) keeps it active.
 */
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
        Boolean active) {}
