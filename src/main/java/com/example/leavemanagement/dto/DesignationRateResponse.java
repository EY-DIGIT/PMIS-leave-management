package com.example.leavemanagement.dto;

import java.util.Map;

/**
 * Read response for one designation rate master row.
 *
 * @param id             DB primary key
 * @param role           Role as per Contract
 * @param projectId      project this rate applies to
 * @param organisationId organisation this rate applies to
 * @param rateCardByYear Year-1..Year-7 monthly rates
 */
public record DesignationRateResponse(
        Long id,
        String role,
        String projectId,
        String organisationId,
        Map<String, Double> rateCardByYear) {}
