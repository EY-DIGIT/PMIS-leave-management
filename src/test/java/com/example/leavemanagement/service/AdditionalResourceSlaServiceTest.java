package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.leavemanagement.client.ActivityDetailsClient;
import com.example.leavemanagement.dto.ActivityAdditionalResourceReport;
import com.example.leavemanagement.dto.ActivityDetailsResponse;
import com.example.leavemanagement.dto.ActivityResourceConfig;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ProjectResource;
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

    private AdditionalResourceSlaService service;

    private static final LocalDate A_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate A_END = LocalDate.of(2026, 12, 31);

    @BeforeEach
    void setUp() {
        service = new AdditionalResourceSlaService(
                activityDetailsClient, attendanceRepository, projectResourceRepository);
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

    private void stubActivity(ActivityResourceConfig... resources) {
        when(activityDetailsClient.getActivityDetails("A1")).thenReturn(Optional.of(
                new ActivityDetailsResponse("D9", A_START, A_END, List.of(resources))));
    }

    private void stubRole(String resId, ProjectResource assignment) {
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue(resId, "P1"))
                .thenReturn(Optional.of(assignment));
    }

    @Test
    void newAdditionalDesignationIsReportedWithSla008() {
        // Lead architect classified "additional" (K=07-Mar). Joins 15-Mar → 8 days → Within 21 Days.
        stubActivity(
                new ActivityResourceConfig("Program Manager", 1, 3.0, 198764.0, LocalDate.of(2026, 1, 6), "planned"),
                new ActivityResourceConfig("Lead architect", 1, 2.0, 512000.0, LocalDate.of(2026, 3, 7), "additional"));
        when(projectResourceRepository.findByProjectId("P1")).thenReturn(List.of());

        MasterResource lead = resource("500", 2L, "New Lead");
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("A1"), any(), any()))
                .thenReturn(List.of(lead));
        stubRole("500", new ProjectResource(lead, "P1", "Lead architect", LocalDate.of(2026, 3, 7)));
        when(attendanceRepository.findMinDateByResourceIdAndActivityIdAndDateBetween(eq(2L), eq("A1"), any(), any()))
                .thenReturn(Optional.of(LocalDate.of(2026, 3, 15)));

        ActivityAdditionalResourceReport report = service.additionalResourceOnboarding("P1", "A1");

        assertThat(report.additionalResources()).hasSize(1);
        ActivityAdditionalResourceReport.AdditionalResource r = report.additionalResources().get(0);
        assertThat(r.slaNumber()).isEqualTo("SLA008");
        assertThat(r.designation()).isEqualTo("Lead architect");
        assertThat(r.originalQuantity()).isZero();
        assertThat(r.additionalQuantity()).isEqualTo(1);
        assertThat(r.resId()).isEqualTo("500");
        assertThat(r.plannedDeploymentDate()).isEqualTo(LocalDate.of(2026, 3, 7));
        assertThat(r.actualOnboardingDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(r.onboardingDays()).isEqualTo(8);
        assertThat(r.slaResult()).isEqualTo("Within 21 Days");
    }

    @Test
    void plannedOnlyConfigHasNoAdditionalRows() {
        stubActivity(
                new ActivityResourceConfig("Program Manager", 1, 3.0, 198764.0, LocalDate.of(2026, 1, 6), "planned"),
                new ActivityResourceConfig("Cloud Architect", 1, 3.0, 211982.0, LocalDate.of(2026, 1, 6))); // null → planned
        when(projectResourceRepository.findByProjectId("P1")).thenReturn(List.of());
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("A1"), any(), any()))
                .thenReturn(List.of());

        assertThat(service.additionalResourceOnboarding("P1", "A1").additionalResources()).isEmpty();
    }

    @Test
    void quantityIncreaseSecondResourceIsAdditional() {
        // PM has a "planned" line (qty 1) AND an "additional" line (qty 1) → additional 1, original 1.
        // The second PM by first-attendance is the additional; K=01-Aug, joins 25-Aug → 24 days → 22-28 band.
        stubActivity(
                new ActivityResourceConfig("Program Manager", 1, 3.0, 198764.0, LocalDate.of(2026, 1, 6), "planned"),
                new ActivityResourceConfig("Program Manager", 1, 3.0, 198764.0, LocalDate.of(2026, 8, 1), "additional"));
        when(projectResourceRepository.findByProjectId("P1")).thenReturn(List.of());

        MasterResource original = resource("427", 1L, "Kuldeep");
        MasterResource additional = resource("500", 2L, "New PM");
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("A1"), any(), any()))
                .thenReturn(List.of(original, additional));
        stubRole("427", new ProjectResource(original, "P1", "Program Manager", LocalDate.of(2026, 1, 1)));
        stubRole("500", new ProjectResource(additional, "P1", "Program Manager", LocalDate.of(2026, 8, 1)));
        when(attendanceRepository.findMinDateByResourceIdAndActivityIdAndDateBetween(eq(1L), eq("A1"), any(), any()))
                .thenReturn(Optional.of(LocalDate.of(2026, 1, 7)));
        when(attendanceRepository.findMinDateByResourceIdAndActivityIdAndDateBetween(eq(2L), eq("A1"), any(), any()))
                .thenReturn(Optional.of(LocalDate.of(2026, 8, 25)));

        ActivityAdditionalResourceReport.AdditionalResource r =
                service.additionalResourceOnboarding("P1", "A1").additionalResources().get(0);
        assertThat(r.originalQuantity()).isEqualTo(1);
        assertThat(r.currentApprovedQuantity()).isEqualTo(2);
        assertThat(r.additionalQuantity()).isEqualTo(1);
        assertThat(r.resId()).isEqualTo("500");
        assertThat(r.onboardingDays()).isEqualTo(24);
        assertThat(r.slaResult()).isEqualTo("More than 21 Days and within 28 Days");
    }

    @Test
    void additionalSlotWithNoAttendanceIsPendingOnboarding() {
        stubActivity(
                new ActivityResourceConfig("Lead architect", 1, 2.0, 512000.0, LocalDate.of(2026, 3, 7), "additional"));
        when(projectResourceRepository.findByProjectId("P1")).thenReturn(List.of());
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("A1"), any(), any()))
                .thenReturn(List.of()); // nobody onboarded yet

        ActivityAdditionalResourceReport.AdditionalResource r =
                service.additionalResourceOnboarding("P1", "A1").additionalResources().get(0);
        assertThat(r.designation()).isEqualTo("Lead architect");
        assertThat(r.additionalQuantity()).isEqualTo(1);
        assertThat(r.resId()).isNull();
        assertThat(r.actualOnboardingDate()).isNull();
        assertThat(r.onboardingDays()).isNull();
        assertThat(r.slaResult()).isEqualTo("Pending Onboarding");
    }
}
