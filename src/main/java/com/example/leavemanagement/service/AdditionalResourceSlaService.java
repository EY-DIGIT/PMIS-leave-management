package com.example.leavemanagement.service;

import com.example.leavemanagement.client.ActivityDetailsClient;
import com.example.leavemanagement.dto.ActivityAdditionalResourceReport;
import com.example.leavemanagement.dto.ActivityBaselineRow;
import com.example.leavemanagement.dto.ActivityDetailsResponse;
import com.example.leavemanagement.dto.ActivityResourceConfig;
import com.example.leavemanagement.entity.ActivityBaseline;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.ActivityBaselineRepository;
import com.example.leavemanagement.repository.AttendanceRepository;
import com.example.leavemanagement.repository.ProjectResourceRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UIDAI SLA 008 (Additional Resource Onboarding), implemented in isolation from the rest of the
 * attendance/leave/cost logic. An additional resource is one created when an activity's approved
 * resource requirement increases over its stored baseline — a new designation, or a higher quantity
 * for an already-configured designation. Replacements (SLA 006 / SLA 009) are excluded.
 *
 * <p>K (start) = the designation's planned deployment date; L (actual) = the additional resource's
 * first attendance date on the activity; result is derived from {@code L − K} in calendar days. No
 * severity is assigned.
 */
@Service
public class AdditionalResourceSlaService {

    private final ActivityDetailsClient activityDetailsClient;
    private final AttendanceRepository attendanceRepository;
    private final ProjectResourceRepository projectResourceRepository;
    private final ActivityBaselineRepository baselineRepository;

    public AdditionalResourceSlaService(
            ActivityDetailsClient activityDetailsClient,
            AttendanceRepository attendanceRepository,
            ProjectResourceRepository projectResourceRepository,
            ActivityBaselineRepository baselineRepository) {
        this.activityDetailsClient = activityDetailsClient;
        this.attendanceRepository = attendanceRepository;
        this.projectResourceRepository = projectResourceRepository;
        this.baselineRepository = baselineRepository;
    }

    /**
     * Captures (snapshots) the activity's current live configuration as the SLA 008 baseline — the
     * "original approved requirement" to compare future increases against. When {@code force} is false
     * and a baseline already exists it is preserved (returned unchanged); {@code force=true} re-captures
     * it from the current config. Capture this at original-config time, before any increase is approved.
     */
    @Transactional
    public List<ActivityBaselineRow> captureBaseline(String activityId, boolean force) {
        ActivityDetailsResponse activity = requireActivity(activityId);
        if (baselineRepository.existsByActivityId(activityId)) {
            if (!force) {
                return currentBaseline(activityId);
            }
            baselineRepository.deleteByActivityId(activityId);
        }
        Map<String, LocalDate> plannedByDesignation = plannedByDesignation(activity);
        activity.requiredByDesignation().forEach((designation, quantity) ->
                baselineRepository.save(new ActivityBaseline(
                        activityId, designation, quantity, plannedByDesignation.get(designation))));
        return currentBaseline(activityId);
    }

    /** The stored baseline for an activity (empty if never captured). */
    @Transactional(readOnly = true)
    public List<ActivityBaselineRow> getBaseline(String activityId) {
        return currentBaseline(activityId);
    }

    /**
     * SLA 008 report for one activity: one row per additional resource (or unfilled additional slot).
     * If no baseline was captured yet, the current config is snapshotted as the baseline first (so the
     * report self-initialises and only shows additions made after this point).
     */
    @Transactional
    public ActivityAdditionalResourceReport additionalResourceOnboarding(String projectId, String activityId) {
        ActivityDetailsResponse activity = requireActivity(activityId);
        LocalDate aStart = activity.startDate();
        LocalDate aEnd = activity.endDate();
        String activityName = activity.activityName() != null ? activity.activityName() : activityId;

        // Baseline: auto-capture the current config the first time so increases are tracked from here on.
        if (!baselineRepository.existsByActivityId(activityId)) {
            captureBaseline(activityId, false);
        }
        Map<String, Integer> baselineQty = baselineRepository.findByActivityId(activityId).stream()
                .collect(Collectors.toMap(ActivityBaseline::getDesignation, ActivityBaseline::getQuantity,
                        (a, b) -> a, LinkedHashMap::new));

        Map<String, Integer> currentQty = activity.requiredByDesignation();
        Map<String, LocalDate> plannedByDesignation = plannedByDesignation(activity);

        // Resources that joined as a replacement fill an existing slot — never an additional one.
        Set<String> replacementIncomings = projectResourceRepository.findByProjectId(projectId).stream()
                .map(ProjectResource::getReplacedByResId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());

        // Actual resources with attendance under the activity, grouped by their stored designation,
        // ordered by first-attendance date; replacement incomings excluded.
        Map<String, List<ResourceOnboarding>> actualByDesignation = new LinkedHashMap<>();
        for (MasterResource resource : attendanceRepository
                .findDistinctResourcesByActivityIdAndDateBetween(activityId, aStart, aEnd)) {
            if (replacementIncomings.contains(resource.getResId())) {
                continue;
            }
            String role = resolveRole(resource.getResId(), projectId);
            if (role == null) {
                continue;
            }
            LocalDate firstAttendance = attendanceRepository
                    .findMinDateByResourceIdAndActivityIdAndDateBetween(resource.getId(), activityId, aStart, aEnd)
                    .orElse(null);
            actualByDesignation.computeIfAbsent(role, d -> new ArrayList<>())
                    .add(new ResourceOnboarding(resource.getResId(), resource.getName(), firstAttendance));
        }
        actualByDesignation.values().forEach(list -> list.sort(
                Comparator.comparing(ResourceOnboarding::firstAttendance,
                        Comparator.nullsLast(Comparator.naturalOrder()))));

        List<ActivityAdditionalResourceReport.AdditionalResource> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : currentQty.entrySet()) {
            String designation = entry.getKey();
            int current = entry.getValue();
            int original = baselineQty.getOrDefault(designation, 0);
            int additional = Math.max(0, current - original);
            if (additional == 0) {
                continue;
            }
            LocalDate plannedDeployment = plannedByDesignation.getOrDefault(designation, aStart);

            List<ResourceOnboarding> eligible = actualByDesignation.getOrDefault(designation, List.of());
            int startIdx = Math.min(original, eligible.size());
            int endIdx = Math.min(original + additional, eligible.size());
            List<ResourceOnboarding> additionalActual = eligible.subList(startIdx, endIdx);

            for (ResourceOnboarding r : additionalActual) {
                LocalDate actual = r.firstAttendance();
                Integer days = actual == null ? null
                        : (int) ChronoUnit.DAYS.between(plannedDeployment, actual);
                String result = actual == null ? "Pending Onboarding" : slaResult008(days);
                rows.add(new ActivityAdditionalResourceReport.AdditionalResource(
                        "SLA008", designation, original, current, additional,
                        r.resId(), r.name(), plannedDeployment, actual, days, result));
            }
            // Additional slots not yet filled by any attending resource → pending onboarding.
            int pending = additional - additionalActual.size();
            for (int i = 0; i < pending; i++) {
                rows.add(new ActivityAdditionalResourceReport.AdditionalResource(
                        "SLA008", designation, original, current, additional,
                        null, null, plannedDeployment, null, null, "Pending Onboarding"));
            }
        }
        return new ActivityAdditionalResourceReport(
                projectId, activityId, activityName, rows.size(), rows);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ActivityDetailsResponse requireActivity(String activityId) {
        ActivityDetailsResponse activity = activityDetailsClient.getActivityDetails(activityId)
                .orElseThrow(() -> new NotFoundException("No activity found with id '" + activityId + "'"));
        if (activity.startDate() == null || activity.endDate() == null) {
            throw new BadRequestException("Activity '" + activityId + "' has no start/end date.");
        }
        return activity;
    }

    private List<ActivityBaselineRow> currentBaseline(String activityId) {
        return baselineRepository.findByActivityId(activityId).stream()
                .map(b -> new ActivityBaselineRow(b.getDesignation(), b.getQuantity(), b.getPlannedDeploymentDate()))
                .toList();
    }

    private Map<String, LocalDate> plannedByDesignation(ActivityDetailsResponse activity) {
        Map<String, LocalDate> map = new LinkedHashMap<>();
        if (activity.resources() != null) {
            for (ActivityResourceConfig r : activity.resources()) {
                if (r.designation() != null && r.plannedDeploymentDate() != null) {
                    map.putIfAbsent(r.designation(), r.plannedDeploymentDate());
                }
            }
        }
        return map;
    }

    private String resolveRole(String resId, String projectId) {
        return projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue(resId, projectId)
                .or(() -> projectResourceRepository
                        .findByResource_ResIdAndProjectIdOrderByAssignmentStartDateDesc(resId, projectId)
                        .stream().findFirst())
                .map(ProjectResource::getRole)
                .orElse(null);
    }

    /** SLA 008 result: {@code <=21} Within; {@code 22..28} More than 21 within 28; {@code >28} More than 28. */
    private static String slaResult008(int days) {
        if (days <= 21) {
            return "Within 21 Days";
        }
        if (days <= 28) {
            return "More than 21 Days and within 28 Days";
        }
        return "More than 28 Days";
    }

    /** An actual resource on the activity with its first attendance date (SLA 008 L). */
    private record ResourceOnboarding(String resId, String name, LocalDate firstAttendance) {}
}
