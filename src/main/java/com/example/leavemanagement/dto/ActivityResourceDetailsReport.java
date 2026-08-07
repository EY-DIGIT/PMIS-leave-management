package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.List;

/**
 * Resource history for a given project + designation (+ organisation): every resource that holds that
 * designation, with the full list of activities each has worked and the period worked on each
 * (assignment window ∩ activity window) plus current status. Lets the UI see who has worked a
 * designation before deciding whether an uploaded resource is a continuation, a new deployment, or a
 * replacement.
 */
public record ActivityResourceDetailsReport(
        String projectId,
        String organisationId,
        String designation,
        List<ResourceHistory> resources) {

    public record ResourceHistory(
            String resourceId,
            String employeeName,
            List<ActivityWork> activities) {}

    public record ActivityWork(
            String activityId,
            String activityName,
            @JsonFormat(pattern = "dd-MM-yyyy") LocalDate activityStartDate,
            @JsonFormat(pattern = "dd-MM-yyyy") LocalDate activityEndDate,
            @JsonFormat(pattern = "dd-MM-yyyy") LocalDate workedFrom,
            @JsonFormat(pattern = "dd-MM-yyyy") LocalDate workedTo,
            String status) {}
}
