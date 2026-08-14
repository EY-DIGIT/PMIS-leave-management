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
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.entity.DesignationRateMaster;
import com.example.leavemanagement.repository.DesignationRateMasterRepository;
import com.example.leavemanagement.repository.MasterResourceRepository;
import com.example.leavemanagement.repository.ProjectResourceRepository;
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

import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class MasterResourceServiceTest {

    @Mock
    private ResourceParser parser;

    @Mock
    private MasterResourceRepository repository;

    @Mock
    private ProjectResourceRepository projectResourceRepository;

    @Mock
    private DesignationRateMasterRepository designationRateMasterRepository;

    private MasterResourceService service;

    @BeforeEach
    void setUp() {
        service = new MasterResourceService(parser, repository, projectResourceRepository, designationRateMasterRepository);
    }

    /** Stubs role validation to pass for any role/project/org combination. */
    private void stubAllRolesValid() {
        when(designationRateMasterRepository.existsByRoleAndProjectIdAndOrganisationId(
                anyString(), anyString(), anyString())).thenReturn(true);
    }

    private MultipartFile anyFile() {
        return new MockMultipartFile("file", "resources.xlsx", null, new byte[] {1});
    }

    /** Stubs repository.save to return whatever MasterResource it was given, with id 1 set. */
    private void stubSaveAssignsId(long id) {
        when(repository.save(any())).thenAnswer(inv -> {
            MasterResource m = inv.getArgument(0);
            setId(m, id);
            return m;
        });
    }

    private void setId(MasterResource resource, long id) {
        try {
            var field = MasterResource.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(resource, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void uploadInsertsBrandNewResourceAndAssignment() {
        stubAllRolesValid();
        ResourceRow row = new ResourceRow(
                "1", "Sanju", "Security Crypto Lead", "Bengaluru", LocalDate.of(2026, 1, 1), null,
                "RFP", "NA", true, null, null);
        when(parser.parse(any())).thenReturn(List.of(row));
        when(repository.findByResId("1")).thenReturn(Optional.empty());
        stubSaveAssignsId(1L);
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.empty());

        ResourceUploadResult result = service.upload(anyFile(), "P1", "ORG1");

        assertThat(result.resourcesStored()).isEqualTo(1);
        ArgumentCaptor<MasterResource> masterCaptor = ArgumentCaptor.forClass(MasterResource.class);
        verify(repository).save(masterCaptor.capture());
        assertThat(masterCaptor.getValue().getResId()).isEqualTo("1");
        assertThat(masterCaptor.getValue().getDateOfJoining()).isEqualTo(LocalDate.of(2026, 1, 1));

        ArgumentCaptor<ProjectResource> assignmentCaptor = ArgumentCaptor.forClass(ProjectResource.class);
        verify(projectResourceRepository).save(assignmentCaptor.capture());
        ProjectResource assignment = assignmentCaptor.getValue();
        assertThat(assignment.getProjectId()).isEqualTo("P1");
        assertThat(assignment.getRole()).isEqualTo("Security Crypto Lead");
        assertThat(assignment.isActive()).isTrue();
    }

    @Test
    void uploadWithSameProjectAndRoleUpdatesAssignmentInPlace() {
        stubAllRolesValid();
        MasterResource resource = new MasterResource("1");
        setId(resource, 1L);
        resource.setDateOfJoining(LocalDate.of(2026, 1, 1));
        when(repository.findByResId("1")).thenReturn(Optional.of(resource));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProjectResource activeAssignment =
                new ProjectResource(resource, "P1", "Security Crypto Lead", LocalDate.of(2026, 1, 1));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.of(activeAssignment));

        // Same project, same role — rate card is refreshed from the designation master.
        DesignationRateMaster desig = new DesignationRateMaster("Security Crypto Lead", "P1", "ORG1");
        desig.setRateCardByYear(Map.of("Year-1", 120000.0));
        when(designationRateMasterRepository.findByRoleAndProjectIdAndOrganisationId(
                "Security Crypto Lead", "P1", "ORG1")).thenReturn(Optional.of(desig));

        ResourceRow row = new ResourceRow(
                "1", "Sanju", "Security Crypto Lead", "Delhi", LocalDate.of(2026, 1, 1), null,
                "RFP", "NA", true, null, null);
        when(parser.parse(any())).thenReturn(List.of(row));

        service.upload(anyFile(), "P1", "ORG1");

        verify(projectResourceRepository, times(1)).save(activeAssignment);
        assertThat(activeAssignment.getRateCardByYear()).containsEntry("Year-1", 120000.0);
        assertThat(activeAssignment.isActive()).isTrue();
    }

    @Test
    void uploadWithChangedRoleClosesOldAssignmentAndOpensNew() {
        stubAllRolesValid();
        MasterResource resource = new MasterResource("1");
        setId(resource, 1L);
        resource.setDateOfJoining(LocalDate.of(2026, 1, 1));
        when(repository.findByResId("1")).thenReturn(Optional.of(resource));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProjectResource activeAssignment =
                new ProjectResource(resource, "P1", "Security Crypto Lead", LocalDate.of(2026, 1, 1));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.of(activeAssignment));

        // Role changes to Principal Architect effective 15-Jul-2026, same project.
        ResourceRow row = new ResourceRow(
                "1", "Sanju", "Principal Architect", "Bengaluru", LocalDate.of(2026, 7, 15), null,
                "RFP", "NA", true, null, null);
        when(parser.parse(any())).thenReturn(List.of(row));

        service.upload(anyFile(), "P1", "ORG1");

        assertThat(activeAssignment.isActive()).isFalse();
        assertThat(activeAssignment.getAssignmentEndDate()).isEqualTo(LocalDate.of(2026, 7, 14));

        ArgumentCaptor<ProjectResource> captor = ArgumentCaptor.forClass(ProjectResource.class);
        verify(projectResourceRepository, times(2)).save(captor.capture());
        ProjectResource newAssignment = captor.getAllValues().get(1);
        assertThat(newAssignment).isNotSameAs(activeAssignment);
        assertThat(newAssignment.getProjectId()).isEqualTo("P1");
        assertThat(newAssignment.getRole()).isEqualTo("Principal Architect");
        assertThat(newAssignment.getAssignmentStartDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(newAssignment.getAssignmentEndDate()).isNull();
        assertThat(newAssignment.isActive()).isTrue();
    }

    @Test
    void uploadRejectsWhenResourceIsActiveInADifferentProject() {
        stubAllRolesValid();
        MasterResource resource = new MasterResource("1");
        setId(resource, 1L);
        resource.setDateOfJoining(LocalDate.of(2026, 1, 1));
        when(repository.findByResId("1")).thenReturn(Optional.of(resource));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProjectResource activeAssignment =
                new ProjectResource(resource, "P1", "Developer", LocalDate.of(2026, 1, 1));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.of(activeAssignment));

        ResourceRow row = new ResourceRow(
                "1", "Sanju", "Lead", "Bengaluru", LocalDate.of(2026, 8, 1), null,
                "RFP", "NA", true, null, null);
        when(parser.parse(any())).thenReturn(List.of(row));

        // Still active on P1 -> uploading under P2 is rejected, not auto-reassigned.
        assertThatThrownBy(() -> service.upload(anyFile(), "P2", "ORG1"))
                .isInstanceOf(com.example.leavemanagement.exception.BadRequestException.class)
                .hasMessage("Resource 1 is already assigned to Project P1.\n"
                        + "Please release the resource from Project P1 before assigning it to another project.");

        assertThat(activeAssignment.isActive()).isTrue(); // untouched
        verify(projectResourceRepository, never()).save(any());
    }

    @Test
    void uploadAllowsReassignmentAfterReleaseFromPreviousProject() {
        stubAllRolesValid();
        MasterResource resource = new MasterResource("1");
        setId(resource, 1L);
        resource.setDateOfJoining(LocalDate.of(2026, 1, 1));
        when(repository.findByResId("1")).thenReturn(Optional.of(resource));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // No active assignment — the P1 one was already released (active=false).
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.empty());

        ResourceRow row = new ResourceRow(
                "1", "Sanju", "Lead", "Bengaluru", LocalDate.of(2026, 8, 1), null,
                "RFP", "NA", true, null, null);
        when(parser.parse(any())).thenReturn(List.of(row));

        service.upload(anyFile(), "P2", "ORG1");

        ArgumentCaptor<ProjectResource> captor = ArgumentCaptor.forClass(ProjectResource.class);
        verify(projectResourceRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getProjectId()).isEqualTo("P2");
        assertThat(captor.getValue().isActive()).isTrue();
    }

    @Test
    void uploadWithLastWorkingDayClosesActiveAssignmentWithoutOpeningNew() {
        stubAllRolesValid();
        MasterResource resource = new MasterResource("1");
        setId(resource, 1L);
        resource.setDateOfJoining(LocalDate.of(2026, 1, 1));
        when(repository.findByResId("1")).thenReturn(Optional.of(resource));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProjectResource activeAssignment =
                new ProjectResource(resource, "P1", "Security Crypto Lead", LocalDate.of(2026, 1, 1));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.of(activeAssignment));

        // Resignation — Last Day of Working now set.
        ResourceRow row = new ResourceRow(
                "1", "Sanju", "Security Crypto Lead", "Bengaluru", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 8, 30), "RFP", "NA", false, null, null);
        when(parser.parse(any())).thenReturn(List.of(row));

        service.upload(anyFile(), "P1", "ORG1");

        verify(projectResourceRepository, times(1)).save(any());
        assertThat(activeAssignment.isActive()).isFalse();
        assertThat(activeAssignment.getAssignmentEndDate()).isEqualTo(LocalDate.of(2026, 8, 30));
    }

    @Test
    void uploadRejoinAfterResignationInsertsNewAssignment() {
        stubAllRolesValid();
        MasterResource resource = new MasterResource("1");
        setId(resource, 1L);
        resource.setDateOfJoining(LocalDate.of(2026, 1, 1));
        when(repository.findByResId("1")).thenReturn(Optional.of(resource));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // No active assignment exists (the prior one ended).
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.empty());

        ResourceRow row = new ResourceRow(
                "1", "Sanju", "Security Architect", "Bengaluru", LocalDate.of(2026, 10, 15), null,
                "RFP", "NA", true, null, null);
        when(parser.parse(any())).thenReturn(List.of(row));

        service.upload(anyFile(), "P1", "ORG1");

        ArgumentCaptor<ProjectResource> captor = ArgumentCaptor.forClass(ProjectResource.class);
        verify(projectResourceRepository, times(1)).save(captor.capture());
        ProjectResource saved = captor.getValue();
        assertThat(saved.getAssignmentStartDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void getRateCardsByProjectReturnsOneFlatDeduplicatedYearList() {
        ProjectResource sanju = new ProjectResource(new MasterResource("1"), "P1", "Dev", LocalDate.now());
        sanju.setRateCardByYear(Map.of("Year-1", 100874.0, "Year-2", 107594.0));
        ProjectResource shreyas = new ProjectResource(new MasterResource("4"), "P1", "Dev", LocalDate.now());
        shreyas.setRateCardByYear(Map.of("Year-1", 127481.0, "Year-3", 146928.0));

        when(projectResourceRepository.findByProjectIdAndActiveTrue("P1")).thenReturn(List.of(sanju, shreyas));

        List<String> rateCardYears = service.getRateCardsByProject("P1");

        // Year-1 appears on both assignments but is listed once; sorted and flat, not per-resource.
        assertThat(rateCardYears).containsExactly("Year-1", "Year-2", "Year-3");
    }

    @Test
    void getRateCardsByProjectReturnsEmptyListWhenNoActiveAssignments() {
        when(projectResourceRepository.findByProjectIdAndActiveTrue("P1")).thenReturn(List.of());

        List<String> rateCardYears = service.getRateCardsByProject("P1");

        assertThat(rateCardYears).isEmpty();
    }

    @Test
    void getResourceReturnsResourceMergedWithActiveAssignment() {
        MasterResource resource = new MasterResource("1");
        setId(resource, 1L);
        when(repository.findByResId("1")).thenReturn(Optional.of(resource));
        ProjectResource assignment = new ProjectResource(resource, "P1", "Dev", LocalDate.now());
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.of(assignment));

        ResourceResponse response = service.getResource("1");

        assertThat(response.active()).isTrue();
        assertThat(response.projectId()).isEqualTo("P1");
        assertThat(response.designationType()).isEqualTo("Dev");
    }

    @Test
    void getResourceReturnsInactiveResponseWhenNoActiveAssignment() {
        MasterResource resource = new MasterResource("1");
        setId(resource, 1L);
        when(repository.findByResId("1")).thenReturn(Optional.of(resource));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.empty());

        ResourceResponse response = service.getResource("1");

        assertThat(response.active()).isFalse();
        assertThat(response.projectId()).isNull();
    }

    @Test
    void getResourceThrowsNotFoundWhenResourceDoesNotExist() {
        when(repository.findByResId("1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getResource("1")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getHistoryReturnsEmptyListWhenResourceUnknown() {
        when(repository.findByResId("1")).thenReturn(Optional.empty());

        assertThat(service.getHistory("1")).isEmpty();
        verify(projectResourceRepository, never()).findByResourceIdOrderByAssignmentStartDateAsc(any());
    }

    @Test
    void updateResourceThrowsNotFoundWhenResourceDoesNotExist() {
        when(repository.findByResId("1")).thenReturn(Optional.empty());
        ResourceUpdateRequest request = new ResourceUpdateRequest(
                "Sanju", null, "Security Crypto Lead", "Bengaluru", Map.of(), "RFP", "NA",
                LocalDate.of(2026, 1, 1), null, null);

        assertThatThrownBy(() -> service.updateResource("1", request)).isInstanceOf(NotFoundException.class);
    }
}
