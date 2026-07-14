package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.ResourceRow;
import com.example.leavemanagement.dto.ResourceResponse;
import com.example.leavemanagement.dto.ResourceUpdateRequest;
import com.example.leavemanagement.dto.ResourceUploadResult;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.MasterResourceRepository;
import com.example.leavemanagement.repository.ProjectResourceRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploads, reads, searches and updates the master resource (workforce) table, plus each
 * resource's project assignment history.
 *
 * <p>A resource ({@code res_id}) is a single, permanent {@link MasterResource} row — its project,
 * role and rate card are tracked separately as {@link ProjectResource} assignment history, since
 * those change over time while the person doesn't. See {@link #applyAssignment} for the upload's
 * assignment logic (cases 1-5).
 */
@Service
public class MasterResourceService {

    private final ResourceParser parser;
    private final MasterResourceRepository repository;
    private final ProjectResourceRepository projectResourceRepository;

    public MasterResourceService(
            ResourceParser parser,
            MasterResourceRepository repository,
            ProjectResourceRepository projectResourceRepository) {
        this.parser = parser;
        this.repository = repository;
        this.projectResourceRepository = projectResourceRepository;
    }

    /**
     * Parses the resource master Excel and upserts every row, assigning each resource to
     * {@code projectId}. A resource can be active in at most one project at a time. For each row:
     *
     * <ul>
     *   <li>Resource has an active assignment on a <b>different</b> project — reject the upload
     *       (see {@link #applyAssignment}). The resource must be released from that project (its
     *       assignment closed via an upload with Last Day of Working set) before it can be
     *       assigned elsewhere.
     *   <li>Resource doesn't exist yet, or has no active assignment — insert a brand-new active
     *       assignment.
     *   <li>Resource has an active assignment on the same project and same role — update employee
     *       fields and the assignment's rate card in place.
     *   <li>Row signals resignation (Last Day of Working set) — close the current active
     *       assignment; no new assignment is opened.
     *   <li>Resource has an active assignment on the same project with a different role — close
     *       the old assignment (its end date is the day before the new row's date of joining) and
     *       open a new one, preserving role history.
     * </ul>
     */
    @Transactional
    public ResourceUploadResult upload(MultipartFile file, String projectId) {
        List<ResourceRow> rows = parser.parse(file);
        int stored = 0;
        for (ResourceRow row : rows) {
            MasterResource resource = repository
                    .findByResId(row.resId())
                    .map(existing -> updateMasterFields(existing, row))
                    .orElseGet(() -> updateMasterFields(new MasterResource(row.resId()), row));
            applyAssignment(resource, row, projectId);
            stored++;
        }
        return new ResourceUploadResult(rows.size(), stored);
    }

    private MasterResource updateMasterFields(MasterResource resource, ResourceRow row) {
        resource.setName(row.name());
        resource.setLocation(row.location());
        resource.setCategory(row.category());
        resource.setCategoryDetails(row.categoryDetails());
        if (resource.getDateOfJoining() == null) {
            resource.setDateOfJoining(row.dateOfJoining()); // company joining date, set once
        }
        return repository.save(resource);
    }

    private void applyAssignment(MasterResource resource, ResourceRow row, String projectId) {
        Optional<ProjectResource> active = projectResourceRepository.findByResourceIdAndActiveTrue(resource.getId());

        if (active.isPresent() && !active.get().getProjectId().equals(projectId)) {
            String activeProjectId = active.get().getProjectId();
            throw new BadRequestException("Resource " + row.resId() + " is already assigned to Project "
                    + activeProjectId + "." + "\nPlease release the resource from Project " + activeProjectId
                    + " before assigning it to another project.");
        }

        if (active.isPresent() && Objects.equals(active.get().getRole(), row.role()) && row.active()) {
            // Same project, same role: routine correction (rate card, etc.) — update in place.
            ProjectResource assignment = active.get();
            assignment.setRateCardByYear(row.rateCardByYear());
            projectResourceRepository.save(assignment);
            return;
        }

        if (active.isPresent() && !row.active()) {
            // Resignation from the active assignment — close it, open nothing new.
            ProjectResource assignment = active.get();
            assignment.setActive(false);
            assignment.setAssignmentEndDate(row.lastDayOfWorking());
            projectResourceRepository.save(assignment);
            return;
        }

        if (active.isPresent()) {
            // Role changed on the same project while still active — close the old assignment.
            ProjectResource previous = active.get();
            previous.setActive(false);
            previous.setAssignmentEndDate(row.dateOfJoining().minusDays(1));
            projectResourceRepository.save(previous);
        }

        if (row.active()) {
            // Brand-new assignment: first-time upload, rejoin, or role change on the same project.
            ProjectResource assignment = new ProjectResource(resource, projectId, row.role(), row.dateOfJoining());
            assignment.setRateCardByYear(row.rateCardByYear());
            projectResourceRepository.save(assignment);
        }
    }

    /** The resource merged with its current active assignment (if any). */
    @Transactional(readOnly = true)
    public ResourceResponse getResource(String resId) {
        MasterResource resource = repository
                .findByResId(resId)
                .orElseThrow(() -> new NotFoundException("No resource with res_id " + resId));
        return toResponse(resource);
    }

    /**
     * Every historical project assignment for a resource, oldest first. Returns an empty list if
     * resId is unknown.
     */
    @Transactional(readOnly = true)
    public List<ResourceResponse> getHistory(String resId) {
        Optional<MasterResource> resource = repository.findByResId(resId);
        if (resource.isEmpty()) {
            return List.of();
        }
        return projectResourceRepository.findByResourceIdOrderByAssignmentStartDateAsc(resource.get().getId())
                .stream()
                .map(assignment -> buildResponse(resource.get(), assignment))
                .toList();
    }

    /**
     * The distinct rate-card years configured (e.g. "Year-1".."Year-7", no amounts) across every
     * currently active assignment under a project — one flat, deduplicated list, not broken out
     * per resource. Returns an empty list if the project has no active assignments.
     */
    @Transactional(readOnly = true)
    public List<String> getRateCardsByProject(String projectId) {
        return projectResourceRepository.findByProjectIdAndActiveTrue(projectId).stream()
                .flatMap(a -> a.getRateCardByYear().keySet().stream())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Search with all-optional filters; unset parameters are not applied. resId/name/emailId/
     * joinedFrom/joinedTo filter master resource fields; designationType/projectId/active filter
     * against each resource's <b>current</b> assignment (a resource with no active assignment
     * never matches designationType/projectId, and only matches active=false).
     */
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
        Specification<MasterResource> spec = buildSpecification(resId, name, emailId, joinedFrom, joinedTo);
        List<ResourceResponse> results = new ArrayList<>();
        for (MasterResource resource : repository.findAll(spec)) {
            ProjectResource assignment =
                    projectResourceRepository.findByResourceIdAndActiveTrue(resource.getId()).orElse(null);
            if (designationType != null
                    && !designationType.isBlank()
                    && (assignment == null || !designationType.equalsIgnoreCase(assignment.getRole()))) {
                continue;
            }
            if (projectId != null
                    && !projectId.isBlank()
                    && (assignment == null || !projectId.equals(assignment.getProjectId()))) {
                continue;
            }
            if (active != null && (assignment != null) != active) {
                continue;
            }
            results.add(buildResponse(resource, assignment));
        }
        return results;
    }

    /**
     * Updates the resource's employee fields, plus its current active assignment's role/rate card
     * in place (if it has one). Does not create, close, or move assignments.
     */
    @Transactional
    public ResourceResponse updateResource(String resId, ResourceUpdateRequest request) {
        MasterResource resource = repository
                .findByResId(resId)
                .orElseThrow(() -> new NotFoundException("No resource with res_id " + resId));
        resource.setName(request.name());
        resource.setEmailId(request.emailId());
        resource.setLocation(request.location());
        resource.setCategory(request.category());
        resource.setCategoryDetails(request.categoryDetails());
        resource.setDateOfJoining(request.dateOfJoining());
        resource.setLastDate(request.lastDate());
        repository.save(resource);

        ProjectResource assignment =
                projectResourceRepository.findByResourceIdAndActiveTrue(resource.getId()).orElse(null);
        if (assignment != null) {
            assignment.setRole(request.designationType());
            assignment.setRateCardByYear(request.rateCardByYear());
            projectResourceRepository.save(assignment);
        }
        return buildResponse(resource, assignment);
    }

    private Specification<MasterResource> buildSpecification(
            String resId, String name, String emailId, LocalDate joinedFrom, LocalDate joinedTo) {
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
        if (joinedFrom != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("dateOfJoining"), joinedFrom));
        }
        if (joinedTo != null) {
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("dateOfJoining"), joinedTo));
        }

        Optional<Specification<MasterResource>> combined = specs.stream().reduce(Specification::and);
        return combined.orElse(null);
    }

    private ResourceResponse toResponse(MasterResource resource) {
        ProjectResource assignment =
                projectResourceRepository.findByResourceIdAndActiveTrue(resource.getId()).orElse(null);
        return buildResponse(resource, assignment);
    }

    private ResourceResponse buildResponse(MasterResource r, ProjectResource a) {
        return new ResourceResponse(
                r.getId(),
                r.getResId(),
                r.getName(),
                r.getEmailId(),
                r.getLocation(),
                r.getCategory(),
                r.getCategoryDetails(),
                r.getDateOfJoining(),
                r.getLastDate(),
                a != null ? a.getProjectId() : null,
                a != null ? a.getRole() : null,
                a != null ? a.getRateCardByYear() : Map.of(),
                a != null ? a.getRateYear() : null,
                a != null,
                a != null ? a.getAssignmentStartDate() : null,
                a != null ? a.getAssignmentEndDate() : null);
    }
}
