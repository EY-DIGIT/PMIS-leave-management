package com.example.leavemanagement.client;

import com.example.leavemanagement.dto.LeavePolicyResponse;
import java.util.Optional;

/** Fetches a project's leave policy from the external leave-policy service. */
public interface LeavePolicyClient {

    /** Empty when the project has no policy configured or the leave-policy service call fails. */
    Optional<LeavePolicyResponse> getLeavePolicy(String projectId);
}
