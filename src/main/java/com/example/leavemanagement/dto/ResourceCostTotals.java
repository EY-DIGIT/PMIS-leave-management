package com.example.leavemanagement.dto;

/**
 * Aggregate totals across all resources in a cost report period.
 */
public record ResourceCostTotals(
        int resourceCount,
        double totalCost,
        double totalRelaxationAmount,
        double totalDeductedAmount) {}
