package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.example.leavemanagement.dto.DesignationRateRow;
import com.example.leavemanagement.dto.DesignationRateUploadResult;
import com.example.leavemanagement.entity.DesignationRateMaster;
import com.example.leavemanagement.entity.ProjectYearMapping;
import com.example.leavemanagement.repository.DesignationRateMasterRepository;
import com.example.leavemanagement.repository.ProjectYearMappingRepository;
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
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class DesignationRateServiceTest {

    @Mock
    private DesignationRateParser parser;

    @Mock
    private DesignationRateMasterRepository repository;

    @Mock
    private ProjectYearMappingRepository yearMappingRepository;

    @Mock
    private ResourceBasedPeriodService resourceBasedPeriodService;

    private DesignationRateService service;

    @BeforeEach
    void setUp() {
        service = new DesignationRateService(parser, repository, yearMappingRepository, resourceBasedPeriodService);
        when(repository.findByRoleAndProjectIdAndOrganisationId(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // No resource-based period by default → year windows fall back to the supplied project dates.
        lenient().when(resourceBasedPeriodService.resolve(any())).thenReturn(Optional.empty());
        // Year-mapping upsert only runs when anchor dates are available — lenient so the no-dates
        // fallback test doesn't trip strict-stubbing.
        lenient().when(yearMappingRepository.findByProjectIdAndOrganisationIdAndRateYear(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(yearMappingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private MultipartFile anyFile() {
        return new org.springframework.mock.web.MockMultipartFile("file", "rates.xlsx", null, new byte[] {1});
    }

    @Test
    void generatesCompoundingRateCardAlignedToProjectYears() {
        when(parser.parse(any())).thenReturn(List.of(new DesignationRateRow("Program Manager", 100000)));

        DesignationRateUploadResult result = service.upload(
                anyFile(), "P1", "ORG1",
                LocalDate.of(2025, 11, 10), LocalDate.of(2032, 5, 10), 5.0);

        assertThat(result.rowsParsed()).isEqualTo(1);

        ArgumentCaptor<DesignationRateMaster> captor = ArgumentCaptor.forClass(DesignationRateMaster.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        Map<String, Double> card = captor.getValue().getRateCardByYear();

        assertThat(card.get("Year-1")).isEqualTo(100000.0);
        assertThat(card.get("Year-2")).isEqualTo(105000.0);
        assertThat(card.get("Year-3")).isEqualTo(110250.0);
        assertThat(card.get("Year-4")).isEqualTo(115762.5);
        // Project spans 2025-11-10 → 2032-05-10 → 7 project years.
        assertThat(card).containsKeys("Year-5", "Year-6", "Year-7");
    }

    @Test
    void anchorsYearWindowsToResourceBasedPhaseStartNotProjectStart() {
        // Project runs 2025-11-10 → 2032-05-10, but the resource-based phase (billing) starts
        // 2027-05-10. Rate Year-1 (the base) must apply from 2027-05-10, stepping yearly to the
        // resource-based end — NOT from the project start.
        when(parser.parse(any())).thenReturn(List.of(new DesignationRateRow("Program Director", 768000)));
        when(resourceBasedPeriodService.resolve("P1")).thenReturn(Optional.of(
                new ResourceBasedPeriodService.ResourceBasedPeriod(
                        LocalDate.of(2027, 5, 10), LocalDate.of(2032, 5, 10))));

        DesignationRateUploadResult result = service.upload(
                anyFile(), "P1", "ORG1",
                LocalDate.of(2025, 11, 10), LocalDate.of(2032, 5, 10), 5.0);

        // Stale windows from any prior anchor are cleared first.
        org.mockito.Mockito.verify(yearMappingRepository).deleteByProjectIdAndOrganisationId("P1", "ORG1");

        // Year-1 window starts at the resource-based phase start.
        assertThat(result.yearMappings().get(0).rateYear()).isEqualTo("Year-1");
        assertThat(result.yearMappings().get(0).effectiveFrom()).isEqualTo(LocalDate.of(2027, 5, 10));
        assertThat(result.yearMappings().get(0).effectiveTo()).isEqualTo(LocalDate.of(2028, 5, 9));
        assertThat(result.yearMappings().get(1).effectiveFrom()).isEqualTo(LocalDate.of(2028, 5, 10));

        // The base rate is Year-1, which now begins at 2027-05-10.
        ArgumentCaptor<DesignationRateMaster> captor = ArgumentCaptor.forClass(DesignationRateMaster.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRateCardByYear().get("Year-1")).isEqualTo(768000.0);
    }

    @Test
    void zeroIncreaseKeepsBaseRateEveryYear() {
        when(parser.parse(any())).thenReturn(List.of(new DesignationRateRow("Dev", 90000)));

        service.upload(anyFile(), "P1", "ORG1",
                LocalDate.of(2025, 1, 1), LocalDate.of(2027, 12, 31), 0.0);

        ArgumentCaptor<DesignationRateMaster> captor = ArgumentCaptor.forClass(DesignationRateMaster.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        Map<String, Double> card = captor.getValue().getRateCardByYear();
        assertThat(card.values()).allMatch(v -> v == 90000.0);
    }

    @Test
    void fallsBackToSevenYearsWhenProjectDatesAbsent() {
        when(parser.parse(any())).thenReturn(List.of(new DesignationRateRow("Dev", 100000)));

        service.upload(anyFile(), "P1", "ORG1", null, null, 10.0);

        ArgumentCaptor<DesignationRateMaster> captor = ArgumentCaptor.forClass(DesignationRateMaster.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        Map<String, Double> card = captor.getValue().getRateCardByYear();
        assertThat(card).hasSize(7);
        assertThat(card.get("Year-1")).isEqualTo(100000.0);
        assertThat(card.get("Year-2")).isEqualTo(110000.0);
        // No project-year mapping is stored without dates.
        org.mockito.Mockito.verify(yearMappingRepository, org.mockito.Mockito.never())
                .save(any(ProjectYearMapping.class));
    }
}
