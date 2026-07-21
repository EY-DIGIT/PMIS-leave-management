package com.example.leavemanagement.dto;

/**
 * Result of uploading a designation rate card Excel.
 *
 * @param projectId      the project this rate card was uploaded for
 * @param organisationId the organisation this rate card was uploaded for
 * @param rowsParsed     number of role rows found in the file
 * @param rolesUpserted  number of roles inserted or updated in the DB
 */
public record DesignationRateUploadResult(
        String projectId,
        String organisationId,
        int rowsParsed,
        int rolesUpserted) {}
