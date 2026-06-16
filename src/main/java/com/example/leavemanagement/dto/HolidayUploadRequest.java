package com.example.leavemanagement.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request body for the "upload public holidays" API.
 *
 * <p>Each holiday's date must fall inside {@code year} (1-Jan to 31-Dec);
 * that rule is enforced in the service layer.
 */
public record HolidayUploadRequest(
        @Min(value = 1970, message = "year must be >= 1970") @Max(value = 9999, message = "year must be <= 9999")
                int year,
        @NotEmpty(message = "at least one holiday is required") @Valid List<HolidayItem> holidays) {}
