package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * The weekend dates within a scope (a month or a year), grouped by day.
 */
public record WeekendDates(List<LocalDate> saturdayDates, List<LocalDate> sundayDates) {}
