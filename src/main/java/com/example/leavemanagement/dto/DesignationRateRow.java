package com.example.leavemanagement.dto;

import java.util.Map;

/**
 * One row parsed from the designation rate card Excel upload.
 *
 * @param role           Role as per Contract (column A)
 * @param rateCardByYear Year-1..Year-7 monthly rates keyed by "Year-1".."Year-7"
 */
public record DesignationRateRow(String role, Map<String, Double> rateCardByYear) {}
