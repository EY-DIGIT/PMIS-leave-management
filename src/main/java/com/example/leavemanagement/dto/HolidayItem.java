package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * A single holiday entry inside an upload request, and also the shape returned
 * when listing holidays.
 */
public record HolidayItem(
        @NotNull(message = "date is required") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate date,
        @NotBlank(message = "name is required") String name) {}
