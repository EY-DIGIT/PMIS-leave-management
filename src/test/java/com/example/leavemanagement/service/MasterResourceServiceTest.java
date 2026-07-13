package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.leavemanagement.dto.ResourceResponse;
import com.example.leavemanagement.dto.ResourceRow;
import com.example.leavemanagement.dto.ResourceUpdateRequest;
import com.example.leavemanagement.dto.ResourceUploadResult;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.MasterResourceRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class MasterResourceServiceTest {

    @Mock
    private ResourceParser parser;

    @Mock
    private MasterResourceRepository repository;

    private MasterResourceService service;

    @BeforeEach
    void setUp() {
        service = new MasterResourceService(parser, repository);
    }

    private MultipartFile anyFile() {
        return new MockMultipartFile("file", "resources.xlsx", null, new byte[] {1});
    }

    @Test
    void uploadInsertsBrandNewResource() {
        ResourceRow row = new ResourceRow(
                "1", "Sanju", "Security Crypto Lead", "Bengaluru", LocalDate.of(2026, 1, 1), null,
                Map.of("Year-1", 100874.0), "RFP", "NA", true);
        when(parser.parse(any())).thenReturn(List.of(row));
        when(repository.findByResIdAndActiveTrue("1")).thenReturn(Optional.empty());

        ResourceUploadResult result = service.upload(anyFile(), "P1");

        assertThat(result.resourcesStored()).isEqualTo(1);
        ArgumentCaptor<MasterResource> captor = ArgumentCaptor.forClass(MasterResource.class);
        verify(repository).save(captor.capture());
        MasterResource saved = captor.getValue();
        assertThat(saved.getResId()).isEqualTo("1");
        assertThat(saved.getDesignationType()).isEqualTo("Security Crypto Lead");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getProjectId()).isEqualTo("P1");
    }

    @Test
    void uploadWithSameDesignationUpdatesCurrentStintInPlace() {
        MasterResource current = new MasterResource("1");
        current.setDesignationType("Security Crypto Lead");
        current.setDateOfJoining(LocalDate.of(2026, 1, 1));
        current.setActive(true);
        when(repository.findByResIdAndActiveTrue("1")).thenReturn(Optional.of(current));

        // Same designation, e.g. a location correction — no designation change.
        ResourceRow row = new ResourceRow(
                "1", "Sanju", "Security Crypto Lead", "Delhi", LocalDate.of(2026, 1, 1), null,
                Map.of("Year-1", 100874.0), "RFP", "NA", true);
        when(parser.parse(any())).thenReturn(List.of(row));

        service.upload(anyFile(), "P1");

        // Only one save — the existing row is updated, no history row is created.
        verify(repository, times(1)).save(current);
        assertThat(current.getLocation()).isEqualTo("Delhi");
        assertThat(current.isActive()).isTrue();
    }

    @Test
    void uploadWithChangedDesignationClosesOldStintAndOpensNew() {
        MasterResource current = new MasterResource("1");
        current.setDesignationType("Security Crypto Lead");
        current.setDateOfJoining(LocalDate.of(2026, 1, 1));
        current.setActive(true);
        when(repository.findByResIdAndActiveTrue("1")).thenReturn(Optional.of(current));

        // Scenario 1: role changes to Principal Architect effective 15-Jul-2026.
        ResourceRow row = new ResourceRow(
                "1", "Sanju", "Principal Architect", "Bengaluru", LocalDate.of(2026, 7, 15), null,
                Map.of("Year-1", 150000.0), "RFP", "NA", true);
        when(parser.parse(any())).thenReturn(List.of(row));

        ResourceUploadResult result = service.upload(anyFile(), "P1");

        assertThat(result.resourcesStored()).isEqualTo(1);
        ArgumentCaptor<MasterResource> captor = ArgumentCaptor.forClass(MasterResource.class);
        verify(repository, times(2)).save(captor.capture());

        MasterResource closedOldStint = captor.getAllValues().get(0);
        assertThat(closedOldStint).isSameAs(current);
        assertThat(closedOldStint.getLastDate()).isEqualTo(LocalDate.of(2026, 7, 14));
        assertThat(closedOldStint.isActive()).isFalse();

        MasterResource newStint = captor.getAllValues().get(1);
        assertThat(newStint).isNotSameAs(current);
        assertThat(newStint.getResId()).isEqualTo("1");
        assertThat(newStint.getDesignationType()).isEqualTo("Principal Architect");
        assertThat(newStint.getDateOfJoining()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(newStint.getLastDate()).isNull();
        assertThat(newStint.isActive()).isTrue();
    }

    @Test
    void uploadWithLastWorkingDaySignalsResignationInPlace() {
        MasterResource current = new MasterResource("1");
        current.setDesignationType("Security Crypto Lead");
        current.setDateOfJoining(LocalDate.of(2026, 1, 1));
        current.setActive(true);
        when(repository.findByResIdAndActiveTrue("1")).thenReturn(Optional.of(current));

        // Scenario 2: resignation — same designation, Last Day of Working now set.
        ResourceRow row = new ResourceRow(
                "1", "Sanju", "Security Crypto Lead", "Bengaluru", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 8, 30), Map.of("Year-1", 100874.0), "RFP", "NA", false);
        when(parser.parse(any())).thenReturn(List.of(row));

        service.upload(anyFile(), "P1");

        verify(repository, times(1)).save(current);
        assertThat(current.getLastDate()).isEqualTo(LocalDate.of(2026, 8, 30));
        assertThat(current.isActive()).isFalse();
    }

    @Test
    void uploadRejoinAfterResignationInsertsNewStint() {
        // No active stint exists (the prior one ended) -> a fresh upload row is a brand-new insert.
        when(repository.findByResIdAndActiveTrue("1")).thenReturn(Optional.empty());

        ResourceRow row = new ResourceRow(
                "1", "Sanju", "Security Architect", "Bengaluru", LocalDate.of(2026, 10, 15), null,
                Map.of("Year-1", 160000.0), "RFP", "NA", true);
        when(parser.parse(any())).thenReturn(List.of(row));

        service.upload(anyFile(), "P1");

        ArgumentCaptor<MasterResource> captor = ArgumentCaptor.forClass(MasterResource.class);
        verify(repository, times(1)).save(captor.capture());
        MasterResource saved = captor.getValue();
        assertThat(saved.getDateOfJoining()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void getRateCardsByProjectReturnsOneFlatDeduplicatedYearList() {
        MasterResource sanju = new MasterResource("1");
        sanju.setRateCardByYear(Map.of("Year-1", 100874.0, "Year-2", 107594.0));
        sanju.setActive(true);
        sanju.setProjectId("P1");

        MasterResource shreyas = new MasterResource("4");
        shreyas.setRateCardByYear(Map.of("Year-1", 127481.0, "Year-3", 146928.0));
        shreyas.setActive(true);
        shreyas.setProjectId("P1");

        when(repository.findByProjectIdAndActiveTrue("P1")).thenReturn(List.of(sanju, shreyas));

        List<String> rateCardYears = service.getRateCardsByProject("P1");

        // Year-1 appears on both resources but is listed once; sorted and flat, not per-resource.
        assertThat(rateCardYears).containsExactly("Year-1", "Year-2", "Year-3");
    }

    @Test
    void getRateCardsByProjectReturnsEmptyListWhenNoActiveResources() {
        when(repository.findByProjectIdAndActiveTrue("P1")).thenReturn(List.of());

        List<String> rateCardYears = service.getRateCardsByProject("P1");

        assertThat(rateCardYears).isEmpty();
    }

    @Test
    void getResourceReturnsActiveStintWhenPresent() {
        MasterResource active = new MasterResource("1");
        active.setActive(true);
        when(repository.findByResIdAndActiveTrue("1")).thenReturn(Optional.of(active));

        ResourceResponse response = service.getResource("1");

        assertThat(response.active()).isTrue();
        verify(repository, never()).findFirstByResIdOrderByDateOfJoiningDesc(any());
    }

    @Test
    void getResourceFallsBackToLatestStintWhenNoneActive() {
        when(repository.findByResIdAndActiveTrue("1")).thenReturn(Optional.empty());
        MasterResource latest = new MasterResource("1");
        latest.setActive(false);
        when(repository.findFirstByResIdOrderByDateOfJoiningDesc("1")).thenReturn(Optional.of(latest));

        ResourceResponse response = service.getResource("1");

        assertThat(response.active()).isFalse();
    }

    @Test
    void getResourceThrowsNotFoundWhenNoStintsExist() {
        when(repository.findByResIdAndActiveTrue("1")).thenReturn(Optional.empty());
        when(repository.findFirstByResIdOrderByDateOfJoiningDesc("1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getResource("1")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateResourceThrowsNotFoundWhenNoActiveStint() {
        when(repository.findByResIdAndActiveTrue("1")).thenReturn(Optional.empty());
        ResourceUpdateRequest request = new ResourceUpdateRequest(
                "Sanju", null, "Security Crypto Lead", "Bengaluru", Map.of(), "RFP", "NA",
                LocalDate.of(2026, 1, 1), null, true, "P1");

        assertThatThrownBy(() -> service.updateResource("1", request)).isInstanceOf(NotFoundException.class);
    }
}
