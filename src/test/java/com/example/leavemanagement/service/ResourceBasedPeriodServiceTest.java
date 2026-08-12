package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.leavemanagement.client.ProjectPhaseClient;
import com.example.leavemanagement.dto.ProjectPhase;
import com.example.leavemanagement.dto.ProjectPhasesResponse;
import com.example.leavemanagement.service.ResourceBasedPeriodService.ResourceBasedPeriod;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResourceBasedPeriodServiceTest {

    @Mock
    private ProjectPhaseClient phaseClient;

    private ResourceBasedPeriod resolve(List<ProjectPhase> phases) {
        when(phaseClient.getPhases("P1")).thenReturn(Optional.of(new ProjectPhasesResponse(phases)));
        return new ResourceBasedPeriodService(phaseClient).resolve("P1").orElse(null);
    }

    @Test
    void mergesOverlappingResourceBasedPhasesIntoOneSpan() {
        // Phase 1 is not resource-based and must be ignored; Phase 2 and Phase 3 overlap.
        ResourceBasedPeriod period = resolve(List.of(
                new ProjectPhase("Planning", LocalDate.of(2026, 5, 10), LocalDate.of(2027, 5, 9), false),
                new ProjectPhase("Build", LocalDate.of(2027, 5, 10), LocalDate.of(2028, 5, 10), true),
                new ProjectPhase("Operate", LocalDate.of(2027, 5, 10), LocalDate.of(2032, 5, 10), true)));

        assertThat(period).isNotNull();
        assertThat(period.from()).isEqualTo(LocalDate.of(2027, 5, 10));
        assertThat(period.to()).isEqualTo(LocalDate.of(2032, 5, 10));
    }

    @Test
    void mergesContiguousResourceBasedPhasesIntoOneSpan() {
        ResourceBasedPeriod period = resolve(List.of(
                new ProjectPhase("A", LocalDate.of(2027, 5, 10), LocalDate.of(2028, 5, 10), true),
                new ProjectPhase("B", LocalDate.of(2028, 5, 10), LocalDate.of(2030, 5, 10), true),
                new ProjectPhase("C", LocalDate.of(2030, 5, 10), LocalDate.of(2032, 5, 10), true)));

        assertThat(period.from()).isEqualTo(LocalDate.of(2027, 5, 10));
        assertThat(period.to()).isEqualTo(LocalDate.of(2032, 5, 10));
    }

    @Test
    void emptyWhenNoResourceBasedPhases() {
        when(phaseClient.getPhases("P1")).thenReturn(Optional.of(new ProjectPhasesResponse(List.of(
                new ProjectPhase("Planning", LocalDate.of(2026, 5, 10), LocalDate.of(2027, 5, 9), false)))));
        assertThat(new ResourceBasedPeriodService(phaseClient).resolve("P1")).isEmpty();
    }

    @Test
    void emptyWhenPhasesUnavailable() {
        when(phaseClient.getPhases("P1")).thenReturn(Optional.empty());
        assertThat(new ResourceBasedPeriodService(phaseClient).resolve("P1")).isEmpty();
    }

    @Test
    void containsChecksWindowBounds() {
        ResourceBasedPeriod period = new ResourceBasedPeriod(
                LocalDate.of(2027, 5, 10), LocalDate.of(2032, 5, 10));
        assertThat(period.contains(LocalDate.of(2027, 5, 10))).isTrue();
        assertThat(period.contains(LocalDate.of(2032, 5, 10))).isTrue();
        assertThat(period.contains(LocalDate.of(2027, 5, 9))).isFalse();
        assertThat(period.contains(LocalDate.of(2032, 5, 11))).isFalse();
    }
}
