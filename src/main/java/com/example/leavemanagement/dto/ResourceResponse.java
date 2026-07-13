package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.Map;

public record ResourceResponse(
        Long id,
        String resId,
        String name,
        String emailId,
        String designationType,
        String location,
        Map<String, Double> rateCardByYear,
        String category,
        String categoryDetails,
        LocalDate dateOfJoining,
        LocalDate lastDate,
        boolean active,
        String projectId) {}
