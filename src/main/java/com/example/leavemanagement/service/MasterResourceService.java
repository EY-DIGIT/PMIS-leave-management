package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.ResourceRow;
import com.example.leavemanagement.dto.ResourceResponse;
import com.example.leavemanagement.dto.ResourceUpdateRequest;
import com.example.leavemanagement.dto.ResourceUploadResult;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.MasterResourceRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploads, reads, searches and updates the master resource (workforce) table.
 *
 * <p>A resource can have several history rows over time (one per designation/employment stint).
 * {@link #upload} decides, per uploaded row, whether to update the current stint in place or
 * close it out and open a new one — see the three cases inline in {@link #upload}.
 */
@Service
public class MasterResourceService {

    private final ResourceParser parser;
    private final MasterResourceRepository repository;

    public MasterResourceService(ResourceParser parser, MasterResourceRepository repository) {
        this.parser = parser;
        this.repository = repository;
    }

    /**
     * Parses the resource master Excel and upserts every row, all under the given project.
     * emailId isn't part of this sheet, so it's left untouched (null for brand-new resources) —
     * set it via the update endpoint instead.
     *
     * <p>For each row, compared against the resource's current active stint (if any):
     *
     * <ul>
     *   <li>No active stint exists (brand-new resource, or a rejoin after a prior stint ended) —
     *       insert a new stint.
     *   <li>An active stint exists with a <b>different</b> designation and the row is still active
     *       — close the old stint (its last day becomes the day before the new row's date of
     *       joining) and insert a new stint. This preserves designation history.
     *   <li>Otherwise (same designation, or the row signals resignation) — update the current
     *       stint in place. No history row is created for routine field corrections or for
     *       marking a resignation.
     * </ul>
     */
    @Transactional
    public ResourceUploadResult upload(MultipartFile file, String projectId) {
        List<ResourceRow> rows = parser.parse(file);
        int stored = 0;
        for (ResourceRow row : rows) {
            Optional<MasterResource> current = repository.findByResIdAndActiveTrue(row.resId());

            if (current.isPresent()
                    && row.active()
                    && !Objects.equals(current.get().getDesignationType(), row.role())) {
                MasterResource previousStint = current.get();
                previousStint.setLastDate(row.dateOfJoining().minusDays(1));
                previousStint.setActive(false);
                repository.save(previousStint);
                applyRow(new MasterResource(row.resId()), row, projectId);
            } else {
                MasterResource stint = current.orElseGet(() -> new MasterResource(row.resId()));
                applyRow(stint, row, projectId);
            }
            stored++;
        }
        return new ResourceUploadResult(rows.size(), stored);
    }

    private void applyRow(MasterResource target, ResourceRow row, String projectId) {
        target.setName(row.name());
        target.setDesignationType(row.role());
        target.setLocation(row.location());
        target.setDateOfJoining(row.dateOfJoining());
        target.setLastDate(row.lastDayOfWorking());
        target.setRateCardByYear(row.rateCardByYear());
        target.setCategory(row.category());
        target.setCategoryDetails(row.categoryDetails());
        target.setActive(row.active());
        target.setProjectId(projectId);
        repository.save(target);
    }

    /** The resource's current stint, falling back to its most recent stint if none is active. */
    @Transactional(readOnly = true)
    public ResourceResponse getResource(String resId) {
        return toResponse(findCurrentOrLatest(resId));
    }

    /** Every historical stint for a resource, oldest first. Returns an empty list if resId is unknown. */
    @Transactional(readOnly = true)
    public List<ResourceResponse> getHistory(String resId) {
        return repository.findByResIdOrderByDateOfJoiningAsc(resId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * The distinct rate-card years configured (e.g. "Year-1".."Year-7", no amounts) across every
     * currently active resource under a project — one flat, deduplicated list, not broken out per
     * resource. Returns an empty list if the project has no active resources.
     */
    @Transactional(readOnly = true)
    public List<String> getRateCardsByProject(String projectId) {
        return repository.findByProjectIdAndActiveTrue(projectId).stream()
                .flatMap(r -> r.getRateCardByYear().keySet().stream())
                .distinct()
                .sorted()
                .toList();
    }

    /** Search with all-optional filters; unset parameters are not applied. Matches across all stints. */
    @Transactional(readOnly = true)
    public List<ResourceResponse> search(
            String resId,
            String name,
            String emailId,
            String designationType,
            String projectId,
            Boolean active,
            LocalDate joinedFrom,
            LocalDate joinedTo) {
        Specification<MasterResource> spec = buildSpecification(
                resId, name, emailId, designationType, projectId, active, joinedFrom, joinedTo);
        return repository.findAll(spec).stream().map(this::toResponse).toList();
    }

    /** Updates the resource's current stint in place. Does not trigger designation-history logic. */
    @Transactional
    public ResourceResponse updateResource(String resId, ResourceUpdateRequest request) {
        MasterResource resource = repository
                .findByResIdAndActiveTrue(resId)
                .orElseThrow(() -> new NotFoundException("No active resource with res_id " + resId));
        resource.setName(request.name());
        resource.setEmailId(request.emailId());
        resource.setDesignationType(request.designationType());
        resource.setLocation(request.location());
        resource.setRateCardByYear(request.rateCardByYear());
        resource.setCategory(request.category());
        resource.setCategoryDetails(request.categoryDetails());
        resource.setDateOfJoining(request.dateOfJoining());
        resource.setLastDate(request.lastDate());
        resource.setActive(request.active());
        resource.setProjectId(request.projectId());
        return toResponse(resource);
    }

    private MasterResource findCurrentOrLatest(String resId) {
        return repository
                .findByResIdAndActiveTrue(resId)
                .or(() -> repository.findFirstByResIdOrderByDateOfJoiningDesc(resId))
                .orElseThrow(() -> new NotFoundException("No resource with res_id " + resId));
    }

    private Specification<MasterResource> buildSpecification(
            String resId,
            String name,
            String emailId,
            String designationType,
            String projectId,
            Boolean active,
            LocalDate joinedFrom,
            LocalDate joinedTo) {
        List<Specification<MasterResource>> specs = new ArrayList<>();
        if (resId != null && !resId.isBlank()) {
            specs.add((root, query, cb) -> cb.equal(root.get("resId"), resId));
        }
        if (name != null && !name.isBlank()) {
            specs.add((root, query, cb) -> cb.like(cb.lower(root.get("name")), "%" + name.toLowerCase() + "%"));
        }
        if (emailId != null && !emailId.isBlank()) {
            specs.add((root, query, cb) ->
                    cb.like(cb.lower(root.get("emailId")), "%" + emailId.toLowerCase() + "%"));
        }
        if (designationType != null && !designationType.isBlank()) {
            specs.add((root, query, cb) -> cb.equal(cb.lower(root.get("designationType")), designationType.toLowerCase()));
        }
        if (projectId != null && !projectId.isBlank()) {
            specs.add((root, query, cb) -> cb.equal(root.get("projectId"), projectId));
        }
        if (active != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        if (joinedFrom != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("dateOfJoining"), joinedFrom));
        }
        if (joinedTo != null) {
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("dateOfJoining"), joinedTo));
        }

        Optional<Specification<MasterResource>> combined = specs.stream().reduce(Specification::and);
        return combined.orElse(null);
    }

    private ResourceResponse toResponse(MasterResource r) {
        return new ResourceResponse(
                r.getId(),
                r.getResId(),
                r.getName(),
                r.getEmailId(),
                r.getDesignationType(),
                r.getLocation(),
                r.getRateCardByYear(),
                r.getCategory(),
                r.getCategoryDetails(),
                r.getDateOfJoining(),
                r.getLastDate(),
                r.isActive(),
                r.getProjectId());
    }
}
