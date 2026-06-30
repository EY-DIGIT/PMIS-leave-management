package com.example.leavemanagement.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record ResourceProjectRequest(
        @NotBlank String attendanceId,
        @NotBlank String employeeName,
        LocalDate joiningDate) {}
