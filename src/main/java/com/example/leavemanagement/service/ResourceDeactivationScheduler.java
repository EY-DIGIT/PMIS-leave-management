package com.example.leavemanagement.service;

import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.repository.ProjectResourceRepository;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightly job that closes active project assignments for resources whose last working date has
 * been reached. This fulfils the "schedule an exit in advance" flow: HR sets
 * {@code active=false} with a future {@code lastDate} via the update API, the assignment stays
 * open until that date, and this scheduler closes it automatically on (or the morning after) the
 * last working day.
 *
 * <p>Runs at 00:05 every day so it fires shortly after midnight, after the date has rolled over.
 */
@Component
public class ResourceDeactivationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ResourceDeactivationScheduler.class);

    private final ProjectResourceRepository projectResourceRepository;

    public ResourceDeactivationScheduler(ProjectResourceRepository projectResourceRepository) {
        this.projectResourceRepository = projectResourceRepository;
    }

    @Scheduled(cron = "0 5 0 * * *")
    @Transactional
    public void deactivateExpiredResources() {
        LocalDate today = LocalDate.now();
        List<ProjectResource> due = projectResourceRepository.findActiveAssignmentsDueForDeactivation(today);
        if (due.isEmpty()) {
            return;
        }
        for (ProjectResource assignment : due) {
            LocalDate exitDate = assignment.getResource().getLastDate();
            assignment.setActive(false);
            assignment.setAssignmentEndDate(exitDate);
            projectResourceRepository.save(assignment);
            log.info("Deactivated assignment for resource {} (project {}) — last working date was {}",
                    assignment.getResource().getResId(), assignment.getProjectId(), exitDate);
        }
        log.info("Nightly deactivation complete: {} assignment(s) closed.", due.size());
    }
}
