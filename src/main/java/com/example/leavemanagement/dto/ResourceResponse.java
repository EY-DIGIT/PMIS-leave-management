package com.example.leavemanagement.dto;

import java.time.LocalDate;

public record ResourceResponse(
        String resId,
        String name,
        String emailId,
        Double rateCard,
        LocalDate dateOfJoining,
        LocalDate lastDate,
        String designationType,
        boolean active) {}
