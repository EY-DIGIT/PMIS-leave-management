package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.ActivityRequest;
import com.example.leavemanagement.dto.ActivityResponse;
import com.example.leavemanagement.entity.Activity;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.ActivityRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {

    private final ActivityRepository activityRepository;

    public ActivityService(ActivityRepository activityRepository) {
        this.activityRepository = activityRepository;
    }

    @Transactional
    public ActivityResponse upsert(String activityId, ActivityRequest request) {
        if (request.startDate() != null && request.endDate() != null
                && request.startDate().isAfter(request.endDate())) {
            throw new BadRequestException("startDate cannot be after endDate.");
        }
        Activity activity = activityRepository.findByActivityId(activityId)
                .orElseGet(Activity::new);
        activity.setActivityId(activityId);
        activity.setActivityName(request.activityName());
        activity.setProjectId(request.projectId());
        activity.setMilestoneId(request.milestoneId());
        activity.setOrganisationId(request.organisationId());
        activity.setStartDate(request.startDate());
        activity.setEndDate(request.endDate());
        return toResponse(activityRepository.save(activity));
    }

    @Transactional
    public ActivityResponse syncDesignationRequirements(String activityId, Map<String, Integer> requirements) {
        Activity activity = activityRepository.findByActivityId(activityId)
                .orElseThrow(() -> new NotFoundException("No activity registered with id '" + activityId + "'"));
        activity.setDesignationRequirements(new LinkedHashMap<>(requirements));
        return toResponse(activityRepository.save(activity));
    }

    @Transactional(readOnly = true)
    public ActivityResponse findById(String activityId) {
        return toResponse(activityRepository.findByActivityId(activityId)
                .orElseThrow(() -> new NotFoundException("No activity registered with id '" + activityId + "'")));
    }

    private ActivityResponse toResponse(Activity a) {
        Map<String, Integer> reqs = a.getDesignationRequirements() != null
                ? a.getDesignationRequirements() : Map.of();
        int total = reqs.values().stream().mapToInt(Integer::intValue).sum();
        return new ActivityResponse(
                a.getActivityId(), a.getActivityName(), a.getProjectId(),
                a.getMilestoneId(), a.getOrganisationId(), a.getStartDate(), a.getEndDate(),
                reqs, total);
    }
}
