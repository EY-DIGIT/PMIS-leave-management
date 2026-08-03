package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.ResourceRow;
import com.example.leavemanagement.dto.ResourceResponse;
import com.example.leavemanagement.dto.ResourceUpdateRequest;
import com.example.leavemanagement.dto.ResourceUploadResult;
import com.example.leavemanagement.entity.DesignationRateMaster;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.DesignationRateMasterRepository;
import com.example.leavemanagement.repository.MasterResourceRepository;
import com.example.leavemanagement.repository.ProjectResourceRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final DesignationRateMasterRepository designationRateMasterRepository;

    public MasterResourceService(
            ResourceParser parser,
            MasterResourceRepository repository,
            ProjectResourceRepository projectResourceRepository,
            DesignationRateMasterRepository designationRateMasterRepository) {
        this.parser = parser;
        this.repository = repository;
        this.projectResourceRepository = projectResourceRepository;
        this.designationRateMasterRepository = designationRateMasterRepository;
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
    /**
     * Parses the resource master Excel and upserts every row, assigning each resource to
     * {@code projectId}. Before upserting, validates that every row's "Role as per Contract"
     * exists in the designation rate master for the given project/organisation — upload the
     * designation rate card first if any role is missing.
     */
    @Transactional
    public ResourceUploadResult upload(MultipartFile file, String projectId, String organisationId) {
        List<ResourceRow> rows = parser.parse(file);
        validateRoles(rows, projectId, organisationId);
        validateReplacements(rows, projectId);
        int stored = 0;
        for (ResourceRow row : rows) {
            MasterResource resource = repository
                    .findByResId(row.resId())
                    .map(existing -> updateMasterFields(existing, row))
                    .orElseGet(() -> updateMasterFields(new MasterResource(row.resId()), row));
            applyAssignment(resource, row, projectId, organisationId);
            stored++;
        }
        return new ResourceUploadResult(rows.size(), stored);
    }

    private void validateRoles(List<ResourceRow> rows, String projectId, String organisationId) {
        List<String> errors = rows.stream()
                .filter(r -> !designationRateMasterRepository
                        .existsByRoleAndProjectIdAndOrganisationId(r.role(), projectId, organisationId))
                .map(r -> "Role '" + r.role() + "' (resource " + r.resId()
                        + ") is not defined in the designation rate master for project "
                        + projectId + " / organisation " + organisationId
                        + ". Upload the designation rate card (POST /api/designation-rates/upload) first.")
                .collect(Collectors.toList());
        if (!errors.isEmpty()) {
            throw new BadRequestException(String.join("\n", errors));
        }
    }

    private void validateReplacements(List<ResourceRow> rows, String projectId) {
        Map<String, ResourceRow> rowByResId = rows.stream()
                .collect(Collectors.toMap(ResourceRow::resId, r -> r));

        // Rule: no two outgoing resources may name the same replacement (one-to-one).
        rows.stream()
                .filter(r -> r.replacedByResId() != null)
                .collect(Collectors.groupingBy(ResourceRow::replacedByResId, Collectors.counting()))
                .entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .findFirst()
                .ifPresent(e -> {
                    throw new BadRequestException(
                            "Resource " + e.getKey() + " is listed as a replacement for more than one resource."
                                    + " A resource can replace only one previous resource.");
                });

        for (ResourceRow row : rows) {
            String replacementId = row.replacedByResId();
            if (replacementId == null) continue;

            if (replacementId.equals(row.resId())) {
                throw new BadRequestException("Resource " + row.resId() + " cannot replace itself.");
            }

            // Resolve role and start date of the replacement from batch or DB.
            String replacementRole;
            LocalDate replacementStartDate;
            ResourceRow replacementRow = rowByResId.get(replacementId);
            if (replacementRow != null) {
                replacementRole = replacementRow.role();
                replacementStartDate = replacementRow.dateOfJoining();
            } else {
                ProjectResource existing = projectResourceRepository
                        .findByResource_ResIdAndProjectIdAndActiveTrue(replacementId, projectId)
                        .orElseThrow(() -> new BadRequestException(
                                "Replacement resource '" + replacementId + "' (listed for resource '"
                                        + row.resId() + "') was not found in the upload or as an active"
                                        + " assignment on project " + projectId + "."));
                replacementRole = existing.getRole();
                replacementStartDate = existing.getAssignmentStartDate();
            }

            // Rule: same designation.
            if (!row.role().equals(replacementRole)) {
                throw new BadRequestException(
                        "Resource '" + row.resId() + "' (role: " + row.role() + ") cannot be replaced by '"
                                + replacementId + "' (role: " + replacementRole
                                + "). The replacement must have the same designation.");
            }

            // Rule: replacement start date must be on or after the outgoing resource's end date.
            if (row.lastDayOfWorking() != null && replacementStartDate != null
                    && replacementStartDate.isBefore(row.lastDayOfWorking())) {
                throw new BadRequestException(
                        "Replacement resource '" + replacementId + "' start date (" + replacementStartDate
                                + ") must be on or after '" + row.resId() + "' end date ("
                                + row.lastDayOfWorking() + ").");
            }

            // Rule: no circular chain (A → B → … → A).
            Set<String> chain = new LinkedHashSet<>();
            chain.add(row.resId());
            String cursor = replacementId;
            while (cursor != null) {
                if (!chain.add(cursor)) {
                    throw new BadRequestException(
                            "Circular replacement detected: " + String.join(" → ", chain) + " → " + cursor);
                }
                ResourceRow next = rowByResId.get(cursor);
                cursor = next != null ? next.replacedByResId() : null;
            }
        }
    }

    private Map<String, Double> fetchRateCard(String role, String projectId, String organisationId) {
        return designationRateMasterRepository
                .findByRoleAndProjectIdAndOrganisationId(role, projectId, organisationId)
                .map(DesignationRateMaster::getRateCardByYear)
                .orElse(Map.of());
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

    private void applyAssignment(
            MasterResource resource, ResourceRow row, String projectId, String organisationId) {
        Optional<ProjectResource> active = projectResourceRepository.findByResourceIdAndActiveTrue(resource.getId());

        if (active.isPresent() && !active.get().getProjectId().equals(projectId)) {
            String activeProjectId = active.get().getProjectId();
            throw new BadRequestException("Resource " + row.resId() + " is already assigned to Project "
                    + activeProjectId + "." + "\nPlease release the resource from Project " + activeProjectId
                    + " before assigning it to another project.");
        }

        if (active.isPresent() && Objects.equals(active.get().getRole(), row.role()) && row.active()) {
            // Same project, same role: refresh the rate card, organisationId, and replacement in place.
            ProjectResource assignment = active.get();
            assignment.setOrganisationId(organisationId);
            assignment.setRateCardByYear(fetchRateCard(row.role(), projectId, organisationId));
            assignment.setReplacedByResId(row.replacedByResId());
            projectResourceRepository.save(assignment);
            return;
        }

        if (active.isPresent() && !row.active()) {
            // Resignation/exit — close the assignment and record the replacement if provided.
            ProjectResource assignment = active.get();
            assignment.setActive(false);
            assignment.setAssignmentEndDate(row.lastDayOfWorking());
            assignment.setReplacedByResId(row.replacedByResId());
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
            assignment.setOrganisationId(organisationId);
            assignment.setRateCardByYear(fetchRateCard(row.role(), projectId, organisationId));
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
            String organisationId,
            Boolean active,
            LocalDate joinedFrom,
            LocalDate joinedTo) {
        Specification<MasterResource> spec = buildSpecification(resId, name, emailId, joinedFrom, joinedTo);
        List<ResourceResponse> results = new ArrayList<>();
        for (MasterResource resource : repository.findAll(spec)) {
            ProjectResource assignment =
                    projectResourceRepository.findByResourceIdAndActiveTrue(resource.getId()).orElseGet(() ->
                            projectResourceRepository
                                    .findByResourceIdOrderByAssignmentStartDateAsc(resource.getId())
                                    .stream()
                                    .reduce((first, second) -> second)
                                    .orElse(null));
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
            if (organisationId != null
                    && !organisationId.isBlank()
                    && (assignment == null || !organisationId.equals(assignment.getOrganisationId()))) {
                continue;
            }
            if (active != null && active != (assignment != null && assignment.isActive())) {
                continue;
            }
            results.add(buildResponse(resource, assignment));
        }
        return results;
    }

    /**
     * Updates the resource's employee fields and active assignment in place, and applies the
     * following active/lastDate business rules when {@code active=false}:
     *
     * <ul>
     *   <li>{@code lastDate = null} — defaults to today; resource becomes inactive immediately.
     *   <li>{@code lastDate <= today} — resource becomes inactive immediately.
     *   <li>{@code lastDate > today} — future exit date saved; resource stays active until that
     *       date, after which the nightly {@code ResourceDeactivationScheduler} closes the
     *       assignment automatically.
     * </ul>
     *
     * When {@code active=true} or unset the resource remains active; {@code lastDate} may still be
     * stored (e.g. a known future exit that has not yet been confirmed).
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

        ProjectResource assignment =
                projectResourceRepository.findByResourceIdAndActiveTrue(resource.getId()).orElse(null);

        if (Boolean.FALSE.equals(request.active())) {
            LocalDate exitDate = request.lastDate() != null ? request.lastDate() : LocalDate.now();
            resource.setLastDate(exitDate);

            if (assignment != null && !exitDate.isAfter(LocalDate.now())) {
                assignment.setActive(false);
                assignment.setAssignmentEndDate(exitDate);
            }
        } else if (Boolean.TRUE.equals(request.active())) {
            resource.setLastDate(null);

            if (assignment == null) {
                assignment = projectResourceRepository
                        .findTopByResourceIdOrderByAssignmentStartDateDesc(resource.getId())
                        .orElse(null);
                if (assignment != null) {
                    assignment.setActive(true);
                    assignment.setAssignmentEndDate(null);
                }
            }
        } else {
            resource.setLastDate(request.lastDate());
        }

        repository.save(resource);

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
                a != null ? a.getAssignmentEndDate() : r.getLastDate(),
                a != null ? a.getProjectId() : null,
                a != null ? a.getRole() : null,
                a != null ? a.getRateCardByYear() : Map.of(),
                a != null ? a.getRateYear() : null,
                a != null && a.isActive(),
                a != null ? a.getAssignmentStartDate() : null,
                a != null ? a.getAssignmentEndDate() : null,
                a != null ? a.getReplacedByResId() : null);
    }
}
