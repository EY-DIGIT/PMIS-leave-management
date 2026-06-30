package com.example.leavemanagement.dto;

import java.time.LocalDate;

public record ResourceProjectResponse(
        String attendanceId,
        String employeeName,
        String email,
        String projectId,
        LocalDate joiningDate) {}
