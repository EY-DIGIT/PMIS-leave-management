package com.example.leavemanagement.client;

import com.example.leavemanagement.dto.ActivityDetailsResponse;
import java.util.Optional;

/** Fetches an activity's planned resource configuration from the external projects service. */
public interface ActivityDetailsClient {

    /** Empty when the activity has no configuration or the projects service call fails. */
    Optional<ActivityDetailsResponse> getActivityDetails(String activityId);
}
