package com.example.leavemanagement.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the phases DTO maps the projects service's real payload shape: a {@code data} object with
 * a {@code phases} array, ISO-datetime dates (with offset) trimmed to calendar dates, and the
 * {@code isResourceBased} flag.
 */
class ProjectPhasesResponseTest {

    private static final String REAL_PAYLOAD = """
            {
              "data": {
                "_type": "ProjectPhases",
                "phases": [
                  { "phase": "1",   "startDate": "2025-11-10T00:00:00+05:30", "endDate": "2027-05-10T23:59:59+05:30", "isResourceBased": false, "isTransactionBased": false },
                  { "phase": "D11", "startDate": "2025-11-10T00:00:00+05:30", "endDate": "2032-05-10T23:59:59+05:30", "isResourceBased": false, "isTransactionBased": false },
                  { "phase": "2",   "startDate": "2027-05-10T00:00:00+05:30", "endDate": "2028-05-10T23:59:59+05:30", "isResourceBased": true,  "isTransactionBased": false },
                  { "phase": "3",   "startDate": "2027-05-10T00:00:00+05:30", "endDate": "2032-05-10T23:59:59+05:30", "isResourceBased": true,  "isTransactionBased": false }
                ]
              },
              "message": null,
              "error": null,
              "status": 200
            }
            """;

    @Test
    void deserializesRealPayloadAndTrimsDatesAndReadsResourceBasedFlag() throws Exception {
        ProjectPhasesResponse response = new ObjectMapper().readValue(REAL_PAYLOAD, ProjectPhasesResponse.class);

        List<ProjectPhase> phases = response.phases();
        assertThat(phases).hasSize(4);

        ProjectPhase phase2 = phases.get(2);
        assertThat(phase2.phase()).isEqualTo("2");
        assertThat(phase2.resourceBased()).isTrue();
        // ISO datetime with +05:30 offset → calendar date only.
        assertThat(phase2.startDate()).isEqualTo(LocalDate.of(2027, 5, 10));
        assertThat(phase2.endDate()).isEqualTo(LocalDate.of(2028, 5, 10));

        assertThat(phases.get(0).resourceBased()).isFalse();
        assertThat(phases.get(3).endDate()).isEqualTo(LocalDate.of(2032, 5, 10));
    }
}
