package com.example.leavemanagement.dto;

/**
 * One row parsed from the designation rate card Excel upload.
 *
 * @param role     Role as per Contract (column A)
 * @param baseRate the project Year-1 monthly rate (column B); later project years are generated from this
 *                 base and the upload's {@code increasePercentage}
 */
public record DesignationRateRow(String role, double baseRate) {}
