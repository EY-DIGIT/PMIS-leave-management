package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.StoredFileInfo;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Saves uploaded Excel files to the NFS mount ({@code file.storage.path}).
 *
 * <p>Directory layout:
 * <pre>
 *   attendance/{projectId}/{milestoneId}/{startDate}_{endDate}_{yyyyMMdd_HHmmss}_{original}
 *   holidays/{yyyyMMdd_HHmmss}_{original}
 *   resources/{projectId}/{yyyyMMdd_HHmmss}_{original}
 * </pre>
 *
 * <p>The {@code startDate_endDate_} prefix on attendance files allows the service to filter
 * by year / quarter / month without a separate database.
 */
@Service
public class FileStorageService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path storageRoot;

    public FileStorageService(@Value("${file.storage.path}") String storagePath) {
        this.storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Cannot initialise file storage root '" + storagePath + "': " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    /**
     * Saves an attendance Excel under {@code attendance/{projectId}/{milestoneId}/}.
     * The filename encodes the period so it can be filtered later:
     * {@code {startDate}_{endDate}_{yyyyMMdd_HHmmss}_{original}}.
     */
    public StoredFileInfo saveAttendance(
            MultipartFile file, String projectId, String milestoneId, LocalDate startDate, LocalDate endDate) {
        String prefix = startDate.format(DATE) + "_" + endDate.format(DATE) + "_";
        String subDir = "attendance/" + projectId + "/" + milestoneId;
        return doSave(file, subDir, prefix, startDate, endDate);
    }

    /**
     * Saves a holiday or resource Excel.
     * The filename is {@code {yyyyMMdd_HHmmss}_{original}}.
     */
    public StoredFileInfo save(MultipartFile file, String subDir) {
        return doSave(file, subDir, "", null, null);
    }

    // ------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------

    /**
     * Lists attendance files for a project/milestone, optionally filtered by year, quarter, or
     * month. A file is included when its stored period overlaps the requested window.
     *
     * @param projectId   required
     * @param milestoneId optional — omit to list all milestones for the project
     * @param year        optional filter — 4-digit year
     * @param quarter     optional filter — 1..4 (requires year)
     * @param month       optional filter — 1..12 (requires year; overrides quarter)
     */
    public List<StoredFileInfo> listAttendance(
            String projectId, String milestoneId, Integer year, Integer quarter, Integer month) {
        String subDir = (milestoneId != null && !milestoneId.isBlank())
                ? "attendance/" + projectId + "/" + milestoneId
                : "attendance/" + projectId;
        List<StoredFileInfo> all = listDir(subDir, true);
        if (year == null) {
            return all;
        }
        LocalDate windowStart = windowStart(year, quarter, month);
        LocalDate windowEnd = windowEnd(year, quarter, month);
        return all.stream()
                .filter(f -> f.periodStart() != null && f.periodEnd() != null)
                .filter(f -> !f.periodEnd().isBefore(windowStart) && !f.periodStart().isAfter(windowEnd))
                .toList();
    }

    /** Lists all files (non-recursive) in {@code subDir}, newest first. */
    public List<StoredFileInfo> list(String subDir) {
        return listDir(subDir, false);
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    /**
     * Resolves {@code relativePath} to an absolute path for streaming.
     * Throws {@link NotFoundException} when the file does not exist.
     */
    public Path resolve(String relativePath) {
        Path resolved = storageRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new BadRequestException("Invalid file path");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new NotFoundException("File not found: " + relativePath);
        }
        return resolved;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private StoredFileInfo doSave(
            MultipartFile file, String subDir, String prefix, LocalDate periodStart, LocalDate periodEnd) {
        String original = sanitize(file.getOriginalFilename());
        String stored = prefix + LocalDateTime.now().format(TS) + "_" + original;
        Path target = safeResolve(subDir).resolve(stored);
        try {
            Files.createDirectories(target.getParent());
            // Use getBytes() — getInputStream() may be exhausted if the parser already read it.
            Files.write(target, file.getBytes());
            String rel = storageRoot.relativize(target).toString().replace('\\', '/');
            return new StoredFileInfo(stored, rel, Files.size(target), lastModified(target), periodStart, periodEnd);
        } catch (IOException e) {
            throw new BadRequestException("Could not store file: " + e.getMessage());
        }
    }

    private List<StoredFileInfo> listDir(String subDir, boolean parsePeriod) {
        Path dir = safeResolve(subDir);
        if (!Files.isDirectory(dir)) {
            // Also scan one level of sub-directories (milestones) when subDir is a project dir
            return scanSubDirs(dir, parsePeriod);
        }
        try {
            return Files.list(dir)
                    .filter(Files::isRegularFile)
                    .map(p -> toInfo(p, parsePeriod))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(StoredFileInfo::uploadedAt).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * When the requested subDir does not itself contain files (it's a project directory whose
     * children are milestone directories), walks one extra level to collect all milestone files.
     */
    private List<StoredFileInfo> scanSubDirs(Path dir, boolean parsePeriod) {
        Path parent = dir.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return List.of();
        }
        try {
            // list the parent (project dir) and walk into each milestone sub-directory
            Path projectDir = parent.resolve(dir.getFileName());
            if (!Files.isDirectory(projectDir)) {
                return List.of();
            }
            return Files.list(projectDir)
                    .filter(Files::isDirectory)
                    .flatMap(milestone -> {
                        try {
                            return Files.list(milestone).filter(Files::isRegularFile);
                        } catch (IOException ex) {
                            return java.util.stream.Stream.empty();
                        }
                    })
                    .map(p -> toInfo(p, parsePeriod))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(StoredFileInfo::uploadedAt).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private StoredFileInfo toInfo(Path p, boolean parsePeriod) {
        try {
            String name = p.getFileName().toString();
            String rel = storageRoot.relativize(p).toString().replace('\\', '/');
            LocalDate[] period = parsePeriod ? parsePeriod(name) : new LocalDate[]{null, null};
            return new StoredFileInfo(name, rel, Files.size(p), lastModified(p), period[0], period[1]);
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Extracts the {@code {startDate}_{endDate}} prefix from an attendance filename.
     * Returns {@code {null, null}} when the name does not start with the expected pattern.
     */
    private static LocalDate[] parsePeriod(String filename) {
        // Pattern: yyyy-MM-dd_yyyy-MM-dd_...
        if (filename.length() < 21) {
            return new LocalDate[]{null, null};
        }
        try {
            LocalDate start = LocalDate.parse(filename.substring(0, 10), DATE);
            LocalDate end = LocalDate.parse(filename.substring(11, 21), DATE);
            return new LocalDate[]{start, end};
        } catch (DateTimeParseException e) {
            return new LocalDate[]{null, null};
        }
    }

    private static LocalDate windowStart(int year, Integer quarter, Integer month) {
        if (month != null) return LocalDate.of(year, month, 1);
        if (quarter != null) return LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
        return LocalDate.of(year, 1, 1);
    }

    private static LocalDate windowEnd(int year, Integer quarter, Integer month) {
        if (month != null) {
            LocalDate first = LocalDate.of(year, month, 1);
            return first.withDayOfMonth(first.lengthOfMonth());
        }
        if (quarter != null) {
            int lastMonth = quarter * 3;
            LocalDate first = LocalDate.of(year, lastMonth, 1);
            return first.withDayOfMonth(first.lengthOfMonth());
        }
        return LocalDate.of(year, 12, 31);
    }

    private Path safeResolve(String subDir) {
        Path resolved = storageRoot.resolve(subDir).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new BadRequestException("Invalid sub-directory path");
        }
        return resolved;
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "file.xlsx";
        return Paths.get(name).getFileName().toString().replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private static LocalDateTime lastModified(Path p) throws IOException {
        FileTime ft = Files.getLastModifiedTime(p);
        return LocalDateTime.ofInstant(ft.toInstant(), ZoneId.systemDefault());
    }
}
