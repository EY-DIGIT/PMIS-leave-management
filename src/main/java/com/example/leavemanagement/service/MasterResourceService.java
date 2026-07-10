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
import java.util.Optional;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Uploads, reads, searches and updates the master resource (workforce) table. */
@Service
public class MasterResourceService {

    private final ResourceParser parser;
    private final MasterResourceRepository repository;

    public MasterResourceService(ResourceParser parser, MasterResourceRepository repository) {
        this.parser = parser;
        this.repository = repository;
    }

    /** Parses the resource master Excel and upserts every row by res_id, all under the given project. */
    @Transactional
    public ResourceUploadResult upload(MultipartFile file, String projectId) {
        List<ResourceRow> rows = parser.parse(file);
        int stored = 0;
        for (ResourceRow row : rows) {
            MasterResource resource =
                    repository.findById(row.resId()).orElse(new MasterResource(row.resId()));
            resource.setName(row.name());
            resource.setEmailId(row.emailId());
            resource.setRateCard(row.rateCard());
            resource.setDateOfJoining(row.dateOfJoining());
            resource.setLastDate(row.lastDate());
            resource.setDesignationType(row.designationType());
            resource.setActive(row.active());
            resource.setProjectId(projectId);
            repository.save(resource);
            stored++;
        }
        return new ResourceUploadResult(rows.size(), stored);
    }

    @Transactional(readOnly = true)
    public ResourceResponse getResource(String resId) {
        return toResponse(findOrThrow(resId));
    }

    /** Search with all-optional filters; unset parameters are not applied. */
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

    @Transactional
    public ResourceResponse updateResource(String resId, ResourceUpdateRequest request) {
        MasterResource resource = findOrThrow(resId);
        resource.setName(request.name());
        resource.setEmailId(request.emailId());
        resource.setRateCard(request.rateCard());
        resource.setDateOfJoining(request.dateOfJoining());
        resource.setLastDate(request.lastDate());
        resource.setDesignationType(request.designationType());
        resource.setActive(request.active());
        resource.setProjectId(request.projectId());
        return toResponse(resource);
    }

    private MasterResource findOrThrow(String resId) {
        return repository.findById(resId)
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
                r.getResId(),
                r.getName(),
                r.getEmailId(),
                r.getRateCard(),
                r.getDateOfJoining(),
                r.getLastDate(),
                r.getDesignationType(),
                r.isActive(),
                r.getProjectId());
    }
}
