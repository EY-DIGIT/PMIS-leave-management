package com.example.leavemanagement.dto;

/**
 * Result of uploading the resource master Excel.
 *
 * @param totalRows rows read from the sheet
 * @param resourcesStored resources created or updated
 */
public record ResourceUploadResult(int totalRows, int resourcesStored) {}
