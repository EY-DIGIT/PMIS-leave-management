package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.leavemanagement.client.ActivityDetailsClient;
import com.example.leavemanagement.dto.ActivityAdditionalResourceReport;
import com.example.leavemanagement.dto.ActivityDetailsResponse;
import com.example.leavemanagement.dto.ActivityResourceConfig;
import com.example.leavemanagement.entity.ActivityBaseline;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.repository.ActivityBaselineRepository;
import com.example.leavemanagement.repository.AttendanceRepository;
import com.example.leavemanagement.repository.ProjectResourceRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdditionalResourceSlaServiceTest {

    @Mock
    private ActivityDetailsClient activityDetailsClient;
    @Mock
    private AttendanceRepository attendanceRepository;
    @Mock
    private ProjectResourceRepository projectResourceRepository;
    @Mock
    private ActivityBaselineRepository baselineRepository;

    private AdditionalResourceSlaService service;

    private static final LocalDate A_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate A_END = LocalDate.of(2026, 12, 31);

    @BeforeEach
    void setUp() {
        service = new AdditionalResourceSlaService(
                activityDetailsClient, attendanceRepository, projectResourceRepository, baselineRepository);
    }

    private MasterResource resource(String resId, long id, String name) {
        MasterResource r = new MasterResource(resId);
        r.setName(name);
        try {
            var f = MasterResource.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(r, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return r;
    }

    private void stubActivityWithProgramManager(int currentQty) {
        when(activityDetailsClient.getActivityDetails("A1")).thenReturn(Optional.of(
                new ActivityDetailsResponse("D9", A_START, A_END,
                        List.of(new ActivityResourceConfig(
                                "Program Manager", currentQty, 3.0, 198764.0, LocalDate.of(2026, 8, 1))))));
    }

    private void stubRole(String resId, ProjectResource assignment) {
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue(resId, "P1"))
                .thenReturn(Optional.of(assignment));
    }

    @Test
    void quantityIncreaseMarksSecondResourceAsAdditionalAndComputesSla008() {
        // Baseline PM=1, current PM=2 → additional 1. K=01-Aug, additional joins 15-Aug → 14 days → Within 21.
        stubActivityWithProgramManager(2);
        when(baselineRepository.existsByActivityId("A1")).thenReturn(true);
        when(baselineRepository.findByActivityId("A1")).thenReturn(List.of(
                new ActivityBaseline("A1", "Program Manager", 1, LocalDate.of(2026, 8, 1))));
        when(projectResourceRepository.findByProjectId("P1")).thenReturn(List.of()); // no replacements

        MasterResource original = resource("427", 1L, "Kuldeep");
        MasterResource additional = resource("500", 2L, "New PM");
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("A1"), any(), any()))
                .thenReturn(List.of(original, additional));
        stubRole("427", new ProjectResource(original, "P1", "Program Manager", LocalDate.of(2026, 1, 1)));
        stubRole("500", new ProjectResource(additional, "P1", "Program Manager", LocalDate.of(2026, 8, 1)));
        when(attendanceRepository.findMinDateByResourceIdAndActivityIdAndDateBetween(eq(1L), eq("A1"), any(), any()))
                .thenReturn(Optional.of(LocalDate.of(2026, 1, 7)));
        when(attendanceRepository.findMinDateByResourceIdAndActivityIdAndDateBetween(eq(2L), eq("A1"), any(), any()))
                .thenReturn(Optional.of(LocalDate.of(2026, 8, 15)));

        ActivityAdditionalResourceReport report = service.additionalResourceOnboarding("P1", "A1");

        assertThat(report.additionalResources()).hasSize(1);
        ActivityAdditionalResourceReport.AdditionalResource r = report.additionalResources().get(0);
        assertThat(r.slaNumber()).isEqualTo("SLA008");
        assertThat(r.designation()).isEqualTo("Program Manager");
        assertThat(r.originalQuantity()).isEqualTo(1);
        assertThat(r.currentApprovedQuantity()).isEqualTo(2);
        assertThat(r.additionalQuantity()).isEqualTo(1);
        assertThat(r.resId()).isEqualTo("500");
        assertThat(r.plannedDeploymentDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(r.actualOnboardingDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(r.onboardingDays()).isEqualTo(14);
        assertThat(r.slaResult()).isEqualTo("Within 21 Days");
    }

    @Test
    void noAdditionalWhenQuantityUnchanged() {
        stubActivityWithProgramManager(1); // current == baseline
        when(baselineRepository.existsByActivityId("A1")).thenReturn(true);
        when(baselineRepository.findByActivityId("A1")).thenReturn(List.of(
                new ActivityBaseline("A1", "Program Manager", 1, LocalDate.of(2026, 8, 1))));
        when(projectResourceRepository.findByProjectId("P1")).thenReturn(List.of());
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("A1"), any(), any()))
                .thenReturn(List.of());

        ActivityAdditionalResourceReport report = service.additionalResourceOnboarding("P1", "A1");
        assertThat(report.additionalResources()).isEmpty();
    }

    @Test
    void additionalSlotWithNoAttendanceIsPendingOnboarding() {
        // Baseline PM=1, current PM=2 → additional 1, but only the original has attendance → 1 pending slot.
        stubActivityWithProgramManager(2);
        when(baselineRepository.existsByActivityId("A1")).thenReturn(true);
        when(baselineRepository.findByActivityId("A1")).thenReturn(List.of(
                new ActivityBaseline("A1", "Program Manager", 1, LocalDate.of(2026, 9, 1))));
        when(projectResourceRepository.findByProjectId("P1")).thenReturn(List.of());

        MasterResource original = resource("427", 1L, "Kuldeep");
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("A1"), any(), any()))
                .thenReturn(List.of(original));
        stubRole("427", new ProjectResource(original, "P1", "Program Manager", LocalDate.of(2026, 1, 1)));
        when(attendanceRepository.findMinDateByResourceIdAndActivityIdAndDateBetween(eq(1L), eq("A1"), any(), any()))
                .thenReturn(Optional.of(LocalDate.of(2026, 1, 7)));

        ActivityAdditionalResourceReport report = service.additionalResourceOnboarding("P1", "A1");

        assertThat(report.additionalResources()).hasSize(1);
        ActivityAdditionalResourceReport.AdditionalResource r = report.additionalResources().get(0);
        assertThat(r.resId()).isNull();
        assertThat(r.actualOnboardingDate()).isNull();
        assertThat(r.onboardingDays()).isNull();
        assertThat(r.slaResult()).isEqualTo("Pending Onboarding");
    }

    @Test
    void newDesignationIsEntirelyAdditionalWith28DayBoundary() {
        // Business Architect not in baseline → all current qty (1) is additional.
        // K=01-Aug, L=29-Aug → 28 days → "More than 21 Days and within 28 Days".
        when(activityDetailsClient.getActivityDetails("A1")).thenReturn(Optional.of(
                new ActivityDetailsResponse("D9", A_START, A_END,
                        List.of(new ActivityResourceConfig(
                                "Business Architect", 1, 3.0, 512000.0, LocalDate.of(2026, 8, 1))))));
        when(baselineRepository.existsByActivityId("A1")).thenReturn(true);
        when(baselineRepository.findByActivityId("A1")).thenReturn(List.of()); // baseline had no Business Architect
        when(projectResourceRepository.findByProjectId("P1")).thenReturn(List.of());

        MasterResource ba = resource("600", 3L, "Arch");
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("A1"), any(), any()))
                .thenReturn(List.of(ba));
        stubRole("600", new ProjectResource(ba, "P1", "Business Architect", LocalDate.of(2026, 8, 1)));
        when(attendanceRepository.findMinDateByResourceIdAndActivityIdAndDateBetween(eq(3L), eq("A1"), any(), any()))
                .thenReturn(Optional.of(LocalDate.of(2026, 8, 29)));

        ActivityAdditionalResourceReport.AdditionalResource r =
                service.additionalResourceOnboarding("P1", "A1").additionalResources().get(0);
        assertThat(r.originalQuantity()).isZero();
        assertThat(r.additionalQuantity()).isEqualTo(1);
        assertThat(r.onboardingDays()).isEqualTo(28);
        assertThat(r.slaResult()).isEqualTo("More than 21 Days and within 28 Days");
    }
}
