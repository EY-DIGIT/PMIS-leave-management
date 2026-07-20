package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Metadata about a file persisted to the NFS storage root.
 *
 * @param filename       stored filename (period-prefixed for attendance files)
 * @param relativePath   path relative to the storage root — pass to the download endpoint
 * @param sizeBytes      file size in bytes
 * @param uploadedAt     server time when the file was written
 * @param periodStart    attendance period start date (null for holiday / resource files)
 * @param periodEnd      attendance period end date   (null for holiday / resource files)
 */
public record StoredFileInfo(
        String filename,
        String relativePath,
        long sizeBytes,
        LocalDateTime uploadedAt,
        LocalDate periodStart,
        LocalDate periodEnd) {}
