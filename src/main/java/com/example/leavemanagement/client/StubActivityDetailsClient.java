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

					new ActivityResourceConfig("Application Operations - Testers (Staging)", 2, 3.0, 136064.00),

					new ActivityResourceConfig("Application Security Engineer", 1, 2.0, 136064.00),

					new ActivityResourceConfig("Architect Dev Ops Automation", 2, 3.0, 211982.00),

					new ActivityResourceConfig("Build and Release Engineer", 1, 3.0, 123088.00),

					new ActivityResourceConfig("Program Manager", 1, 3.0, 198764.00),

					new ActivityResourceConfig("Security Crypto Lead", 1, 3.0, 175600.00),

					new ActivityResourceConfig("Developer - Portals and Mobile Applications", 3, 3.0, 115420.00)));

    @Override
    public Optional<ActivityDetailsResponse> getActivityDetails(String activityId) {
        if (activityId == null || activityId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(SAMPLE);
    }
}
