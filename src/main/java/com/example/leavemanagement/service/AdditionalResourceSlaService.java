package com.example.leavemanagement.service;

import com.example.leavemanagement.client.ActivityDetailsClient;
import com.example.leavemanagement.dto.ActivityAdditionalResourceReport;
import com.example.leavemanagement.dto.ActivityDetailsResponse;
import com.example.leavemanagement.dto.ActivityResourceConfig;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
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
 * attendance/leave/cost logic. Additional resources are identified directly from the activity
 * configuration's {@code resourceClassification} field: a config line marked "additional" is an
 * approved additional requirement (a "planned" line is the baseline). Replacements (SLA 006 / SLA 009)
 * are excluded.
 *
 * <p>K (start) = the additional line's planned deployment date; L (actual) = the additional resource's
 * first attendance date on the activity; result is derived from {@code L − K} in calendar days:
 * {@code <=21} "Within 21 Days", {@code 22..28} "More than 21 Days and within 28 Days", {@code >28}
 * "More than 28 Days", or "Pending Onboarding" (no attendance yet). No severity is assigned.
 */
@Service
public class AdditionalResourceSlaService {

    private final ActivityDetailsClient activityDetailsClient;
    private final AttendanceRepository attendanceRepository;
    private final ProjectResourceRepository projectResourceRepository;

    public AdditionalResourceSlaService(
            ActivityDetailsClient activityDetailsClient,
            AttendanceRepository attendanceRepository,
            ProjectResourceRepository projectResourceRepository) {
        this.activityDetailsClient = activityDetailsClient;
        this.attendanceRepository = attendanceRepository;
        this.projectResourceRepository = projectResourceRepository;
    }

    /** SLA 008 report for one activity: one row per additional resource (or unfilled additional slot). */
    @Transactional(readOnly = true)
    public ActivityAdditionalResourceReport additionalResourceOnboarding(String projectId, String activityId) {
        ActivityDetailsResponse activity = activityDetailsClient.getActivityDetails(activityId)
                .orElseThrow(() -> new NotFoundException("No activity found with id '" + activityId + "'"));
        if (activity.startDate() == null || activity.endDate() == null) {
            throw new BadRequestException("Activity '" + activityId + "' has no start/end date.");
        }
        LocalDate aStart = activity.startDate();
        LocalDate aEnd = activity.endDate();
        String activityName = activity.activityName() != null ? activity.activityName() : activityId;

        // Split the activity config into planned vs additional quantities per designation (from the
        // resourceClassification field). K = the additional line's planned deployment date.
        Map<String, Integer> plannedQty = new LinkedHashMap<>();
        Map<String, Integer> additionalQty = new LinkedHashMap<>();
        Map<String, LocalDate> additionalPlanned = new LinkedHashMap<>();
        for (ActivityResourceConfig c : activity.resources() == null ? List.<ActivityResourceConfig>of() : activity.resources()) {
            if (c.designation() == null || c.quantity() == null) {
                continue;
            }
            if (c.isAdditional()) {
                additionalQty.merge(c.designation(), c.quantity(), Integer::sum);
                if (c.plannedDeploymentDate() != null) {
                    additionalPlanned.putIfAbsent(c.designation(), c.plannedDeploymentDate());
                }
            } else {
                plannedQty.merge(c.designation(), c.quantity(), Integer::sum);
            }
        }

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
        for (Map.Entry<String, Integer> entry : additionalQty.entrySet()) {
            String designation = entry.getKey();
            int additional = entry.getValue();
            if (additional <= 0) {
                continue;
            }
            int original = plannedQty.getOrDefault(designation, 0);
            int current = original + additional;
            LocalDate plannedDeployment = additionalPlanned.getOrDefault(designation, aStart);

            // Planned occupants (the first `original` by first-attendance) fill the baseline slots;
            // the next `additional` are the additional resources.
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
