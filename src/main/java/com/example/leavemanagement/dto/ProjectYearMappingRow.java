package com.example.leavemanagement.dto;

import java.time.LocalDate;

/**
 * One project-year-to-date-range mapping entry, returned in the designation rate upload response
 * and the year-mapping query endpoint.
 *
 * @param rateYear     rate year label, e.g. "Year-1"
 * @param effectiveFrom first date this rate year is active (inclusive)
 * @param effectiveTo   last date this rate year is active (inclusive)
 */
public record ProjectYearMappingRow(String rateYear, LocalDate effectiveFrom, LocalDate effectiveTo) {}
