package com.example.leavemanagement.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ActivityDetailsResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String REAL_PAYLOAD = """
            {
              "data": {
                "_type": "Activity",
                "id": "90c68dcd-9f91-4a8d-89f2-3bb0ca9dd32d",
                "name": "D9 - Resource Deployment - Q1",
                "startDate": "2027-05-11T00:00:00+05:30",
                "endDate": "2027-08-10T23:59:59+05:30",
                "resources": [
                  {"designation": "Application Operations - Testers (Staging)", "quantity": 2, "duration": "3.00", "monthlyRate": "136064.00", "computedCost": "816384.00"},
                  {"designation": "Architect Dev Ops Automation", "quantity": 2, "duration": "3.00", "monthlyRate": "211982.00", "computedCost": "1271892.00"}
                ],
                "resourceCostTotal": "2360404.00",
                "status": "not_completed"
              },
              "message": null,
              "error": null,
              "status": 200
            }
            """;

    @Test
    void parsesRealProjectsServiceActivityPayload() throws Exception {
        ActivityApiResponse envelope = mapper.readValue(REAL_PAYLOAD, ActivityApiResponse.class);
        ActivityDetailsResponse activity = envelope.data();

        assertThat(activity).isNotNull();
        assertThat(activity.activityName()).isEqualTo("D9 - Resource Deployment - Q1");
        assertThat(activity.startDate()).isEqualTo(LocalDate.of(2027, 5, 11));
        assertThat(activity.endDate()).isEqualTo(LocalDate.of(2027, 8, 10));
        assertThat(activity.resources()).hasSize(2);
        assertThat(activity.requiredByDesignation())
                .containsEntry("Architect Dev Ops Automation", 2);
        assertThat(activity.durationByDesignation())
                .containsEntry("Architect Dev Ops Automation", 3.0);
        assertThat(activity.monthlyRateByDesignation())
                .containsEntry("Architect Dev Ops Automation", 211982.0);
    }
}
