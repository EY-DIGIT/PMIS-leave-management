package com.example.leavemanagement.dto;

import java.util.List;

/**
 * Wrapper returned by quarterly/yearly cost report endpoints.
 * Contains per-resource rows plus an aggregate {@link ResourceCostTotals} summary.
 */
public record ResourceCostResult(
        String period,
        int resourceCount,
        ResourceCostTotals totals,
        List<ResourceCostSummary> resources) {}
