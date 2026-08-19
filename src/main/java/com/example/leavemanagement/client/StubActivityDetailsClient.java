package com.example.leavemanagement.client;

import com.example.leavemanagement.dto.ActivityDetailsResponse;
import com.example.leavemanagement.dto.ActivityResourceConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local activity client: returns the same sample activity configuration for every activityId, with
 * no external API call. Only active when {@code activity-service.mock=true} is set explicitly (e.g.
 * for offline dev/tests) — {@link RestActivityDetailsClient} (the real projects service) is the
 * default.
 */
@Component
@ConditionalOnProperty(name = "activity-service.mock", havingValue = "true")
public class StubActivityDetailsClient implements ActivityDetailsClient {

    private static final ActivityDetailsResponse SAMPLE = new ActivityDetailsResponse(
    		"D9 - Resource Deployment - Q1",
            LocalDate.of(2026, 1, 7),
            LocalDate.of(2026, 4, 6),
            List.of(

					new ActivityResourceConfig("Senior Consultant Networking", 1, 3.0, 136064.00, LocalDate.of(2026, 1, 6), "planned"),

					new ActivityResourceConfig("Consultant (Process/ procurement /security)", 1, 2.0, 136064.00, LocalDate.of(2026, 1, 6), "planned"),

					new ActivityResourceConfig("Cloud Architect", 1, 3.0, 211982.00, LocalDate.of(2026, 1, 6), "planned"),

					new ActivityResourceConfig("Senior Consultant change Management", 1, 3.0, 123088.00, LocalDate.of(2026, 1, 6), "planned"),

					new ActivityResourceConfig("Program Manager", 1, 3.0, 198764.00, LocalDate.of(2026, 1, 6), "planned"),

					new ActivityResourceConfig("Cyber Security Architect", 1, 3.0, 175600.00, LocalDate.of(2026, 1, 6), "planned"),

					new ActivityResourceConfig("Applications Engineer", 1, 3.0, 115420.00, LocalDate.of(2026, 1, 6), "planned"),

					new ActivityResourceConfig("Lead architect", 1, 2.0, 512000.00, LocalDate.of(2026, 3, 7), "additional")));

    @Override
    public Optional<ActivityDetailsResponse> getActivityDetails(String activityId) {
        if (activityId == null || activityId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(SAMPLE);
    }
}
