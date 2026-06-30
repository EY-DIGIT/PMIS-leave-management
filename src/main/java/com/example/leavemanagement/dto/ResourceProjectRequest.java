package com.example.leavemanagement.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record ResourceProjectRequest(
        @NotBlank String attendanceId,
        @NotBlank String employeeName,
        @Email String email,
        LocalDate joiningDate) {}
