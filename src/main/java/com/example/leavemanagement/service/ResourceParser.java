package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.ResourceRow;
import com.example.leavemanagement.exception.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Reads the resource master spreadsheet into rows ready to upsert.
 *
 * <p>Expected layout (one header row, data from row 2):
 * <ul>
 *   <li>A — Attendance ID, B — Employee Name, C — Role as per Contract, D — Location
 *   <li>E — Date of Joining, F — Last Day of Working (optional)
 *   <li>G — Category (RFP/CCN/ASG), H — CCN/ASG Details
 *   <li>I — Replaced By Resource ID (optional; the Attendance ID of the incoming replacement)
 *   <li>J — Replacement Notification Date (optional; the SLA 009 "Date of notification")
 * </ul>
 *
 * <p>Year-1..Year-7 rate cards are no longer in the resource Excel — they are resolved from the
 * designation rate master at upload time based on the row's "Role as per Contract". Upload the
 * designation rate card (POST /api/designation-rates/upload) before uploading the resource master.
 */
@Component
public class ResourceParser {

    private static final int HEADER_ROWS = 1;

    private static final int COL_RES_ID = 0;
    private static final int COL_NAME = 1;
    private static final int COL_ROLE = 2;
    private static final int COL_LOCATION = 3;
    private static final int COL_DATE_OF_JOINING = 4;
    private static final int COL_LAST_DAY_OF_WORKING = 5;
    private static final int COL_CATEGORY = 6;           // G
    private static final int COL_CATEGORY_DETAILS = 7;   // H
    private static final int COL_REPLACED_BY_RES_ID = 8; // I
    private static final int COL_REPLACEMENT_NOTIFIED_DATE = 9; // J

    private final DataFormatter formatter = new DataFormatter();

    public List<ResourceRow> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("A resource master Excel file is required (form field 'file')");
        }

        List<ResourceRow> rows = new ArrayList<>();
        try (InputStream in = file.getInputStream();
                Workbook workbook = WorkbookFactory.create(in)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new BadRequestException("The workbook has no sheets");
            }
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            validateHeaderFormat(sheet, evaluator);

            for (Row row : sheet) {
                if (row.getRowNum() < HEADER_ROWS) {
                    continue; // column-label header row
                }
                if (isBlank(row.getCell(COL_RES_ID))) {
                    continue; // skip blank trailing rows
                }
                int humanRow = row.getRowNum() + 1;
                LocalDate lastDayOfWorking = readDate(
                        row.getCell(COL_LAST_DAY_OF_WORKING), evaluator, humanRow, "Last Day of Working", false);
                Cell replacedByCell = row.getCell(COL_REPLACED_BY_RES_ID);
                String replacedByResId = text(replacedByCell, evaluator);
                if (!replacedByResId.isBlank() && looksLikeDate(replacedByCell, replacedByResId)) {
                    throw new BadRequestException(
                            "Column I (Replaced By Resource ID) for resource '"
                                    + text(row.getCell(COL_RES_ID), evaluator) + "' (row " + humanRow
                                    + ") contains a date '" + replacedByResId + "'. This column must be the "
                                    + "incoming replacement's Attendance ID (e.g. 435), or left blank. Put the "
                                    + "last working date in column F, and the replacement notification date in "
                                    + "column J (Replacement Notification Date).");
                }
                LocalDate replacementNotifiedDate = readDate(
                        row.getCell(COL_REPLACEMENT_NOTIFIED_DATE), evaluator, humanRow,
                        "Replacement Notification Date", false);
                rows.add(new ResourceRow(
                        text(row.getCell(COL_RES_ID), evaluator),
                        readName(row.getCell(COL_NAME), evaluator, humanRow),
                        text(row.getCell(COL_ROLE), evaluator),
                        text(row.getCell(COL_LOCATION), evaluator),
                        readDate(row.getCell(COL_DATE_OF_JOINING), evaluator, humanRow, "Date of Joining", true),
                        lastDayOfWorking,
                        text(row.getCell(COL_CATEGORY), evaluator),
                        text(row.getCell(COL_CATEGORY_DETAILS), evaluator),
                        lastDayOfWorking == null,
                        replacedByResId.isBlank() ? null : replacedByResId,
                        replacementNotifiedDate));
            }
        } catch (IOException e) {
            throw new BadRequestException("Could not read the Excel file: " + e.getMessage());
        }

        if (rows.isEmpty()) {
            throw new BadRequestException("The Excel file contains no resource rows");
        }
        return rows;
    }

    /**
     * Rejects a wrong file (e.g. the designation rate card or an attendance sheet) before parsing, by
     * checking the header row's discriminating columns: A = "Attendance ID", C = "Role as per Contract".
     */
    private void validateHeaderFormat(Sheet sheet, FormulaEvaluator evaluator) {
        Row header = sheet.getRow(0);
        String colA = header == null ? "" : normalize(text(header.getCell(COL_RES_ID), evaluator));
        String colC = header == null ? "" : normalize(text(header.getCell(COL_ROLE), evaluator));
        if (!colA.equals("attendanceid") || !colC.equals("roleaspercontract")) {
            throw new BadRequestException(
                    "Invalid resource master file format. Expected the resource master template with a header "
                            + "row: 'Attendance ID | Employee Name | Role as per Contract | Location | Date of "
                            + "Joining | Last Day of Working | Category (RFP/CCN/ASG) | CCN/ASG Details'. "
                            + "Please upload the resource master file (not the designation rate card, attendance, "
                            + "or another file).");
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private String readName(Cell cell, FormulaEvaluator evaluator, int humanRow) {
        String name = text(cell, evaluator);
        if (name.isEmpty()) {
            throw new BadRequestException("Row %d: Employee Name (column B) is missing".formatted(humanRow));
        }
        return name;
    }

    private LocalDate readDate(
            Cell cell, FormulaEvaluator evaluator, int humanRow, String columnLabel, boolean required) {
        if (isBlank(cell)) {
            if (required) {
                throw new BadRequestException("Row %d: %s is missing".formatted(humanRow, columnLabel));
            }
            return null;
        }
        if (effectiveType(cell, evaluator) == CellType.NUMERIC) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String raw = text(cell, evaluator);
        // Strip time portion if present ("2026-01-01 0:00:00" → "2026-01-01")
        String datePart = raw.contains(" ") ? raw.substring(0, raw.indexOf(' ')) : raw;
        try {
            return LocalDate.parse(datePart);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(
                    "Row %d: invalid %s '%s' (expected an Excel date or yyyy-MM-dd)"
                            .formatted(humanRow, columnLabel, raw));
        }
    }

    private boolean isBlank(Cell cell) {
        return cell == null
                || cell.getCellType() == CellType.BLANK
                || (cell.getCellType() == CellType.STRING
                        && cell.getStringCellValue().trim().isEmpty());
    }

    private CellType effectiveType(Cell cell, FormulaEvaluator evaluator) {
        return cell.getCellType() == CellType.FORMULA ? evaluator.evaluateFormulaCell(cell) : cell.getCellType();
    }

    /**
     * True when the "Replaced By Resource ID" cell holds a date rather than a res_id — either a
     * date-formatted numeric Excel cell, or a string like "2/10/26" / "2026-02-10". Attendance IDs
     * never contain date separators, so this only trips on a misplaced date.
     */
    private boolean looksLikeDate(Cell cell, String text) {
        if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return true;
        }
        return text.trim().matches("\\d{1,4}[/-]\\d{1,2}[/-]\\d{1,4}");
    }

    private String text(Cell cell, FormulaEvaluator evaluator) {
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim();
    }
}
