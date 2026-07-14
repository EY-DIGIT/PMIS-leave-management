package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.ResourceRow;
import com.example.leavemanagement.exception.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
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
 * <p>Expected layout of the first sheet — two header rows (a merged group-heading row, then the
 * column-label row), both skipped:
 *
 * <ul>
 *   <li>Column A — Attendance ID, B — Employee Name, C — Role as per Contract, D — Location,
 *       E — Date of Joining, F — Last Day of Working (optional).
 *   <li>Columns G..M — Year-1..Year-7 rate card (blank years are omitted from the map).
 *   <li>Column N — Category (RFP/CCN/ASG), O — CCN / ASG details.
 * </ul>
 *
 * A resource is considered active when Last Day of Working is blank. Fully blank rows are
 * ignored. Any malformed row raises a {@code 400}.
 */
@Component
public class ResourceParser {

    private static final int HEADER_ROWS = 2;

    private static final int COL_RES_ID = 0;
    private static final int COL_NAME = 1;
    private static final int COL_ROLE = 2;
    private static final int COL_LOCATION = 3;
    private static final int COL_DATE_OF_JOINING = 4;
    private static final int COL_LAST_DAY_OF_WORKING = 5;
    private static final int COL_YEAR_1 = 6;
    private static final int YEAR_COLUMNS = 7; // Year-1..Year-7, columns G..M
    private static final int COL_CATEGORY = COL_YEAR_1 + YEAR_COLUMNS; // N
    private static final int COL_CATEGORY_DETAILS = COL_CATEGORY + 1; // O

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

            for (Row row : sheet) {
                if (row.getRowNum() < HEADER_ROWS) {
                    continue; // group-heading row + column-label row
                }
                if (isBlank(row.getCell(COL_RES_ID))) {
                    continue; // skip fully blank trailing rows
                }
                int humanRow = row.getRowNum() + 1;
                LocalDate lastDayOfWorking = readDate(
                        row.getCell(COL_LAST_DAY_OF_WORKING), evaluator, humanRow, "Last Day of Working", false);
                rows.add(new ResourceRow(
                        text(row.getCell(COL_RES_ID), evaluator),
                        readName(row.getCell(COL_NAME), evaluator, humanRow),
                        text(row.getCell(COL_ROLE), evaluator),
                        text(row.getCell(COL_LOCATION), evaluator),
                        readDate(row.getCell(COL_DATE_OF_JOINING), evaluator, humanRow, "Date of Joining", true),
                        lastDayOfWorking,
                        readRateCardByYear(row, evaluator, humanRow),
                        text(row.getCell(COL_CATEGORY), evaluator),
                        text(row.getCell(COL_CATEGORY_DETAILS), evaluator),
                        lastDayOfWorking == null));
            }
        } catch (IOException e) {
            throw new BadRequestException("Could not read the Excel file: " + e.getMessage());
        }

        if (rows.isEmpty()) {
            throw new BadRequestException("The Excel file contains no resource rows");
        }
        return rows;
    }

    private String readName(Cell cell, FormulaEvaluator evaluator, int humanRow) {
        String name = text(cell, evaluator);
        if (name.isEmpty()) {
            throw new BadRequestException("Row %d: Employee Name (column B) is missing".formatted(humanRow));
        }
        return name;
    }

    private Map<String, Double> readRateCardByYear(Row row, FormulaEvaluator evaluator, int humanRow) {
        Map<String, Double> rates = new LinkedHashMap<>();
        for (int i = 0; i < YEAR_COLUMNS; i++) {
            Cell cell = row.getCell(COL_YEAR_1 + i);
            if (isBlank(cell)) {
                continue;
            }
            String yearLabel = "Year-" + (i + 1);
            if (effectiveType(cell, evaluator) == CellType.NUMERIC) {
                rates.put(yearLabel, cell.getNumericCellValue());
                continue;
            }
            String text = text(cell, evaluator);
            try {
                rates.put(yearLabel, Double.parseDouble(text));
            } catch (NumberFormatException e) {
                throw new BadRequestException(
                        "Row %d: invalid %s rate '%s'".formatted(humanRow, yearLabel, text));
            }
        }
        return rates;
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
        String text = text(cell, evaluator);
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(
                    "Row %d: invalid %s '%s' (expected an Excel date or yyyy-MM-dd)"
                            .formatted(humanRow, columnLabel, text));
        }
    }

    private boolean isBlank(Cell cell) {
        return cell == null
                || cell.getCellType() == CellType.BLANK
                || (cell.getCellType() == CellType.STRING
                        && cell.getStringCellValue().trim().isEmpty());
    }

    /** The cell's real value type — for a FORMULA cell, the type its calculated result evaluates to. */
    private CellType effectiveType(Cell cell, FormulaEvaluator evaluator) {
        return cell.getCellType() == CellType.FORMULA ? evaluator.evaluateFormulaCell(cell) : cell.getCellType();
    }

    /** Formats a cell's calculated value — resolves formulas via {@code evaluator} rather than printing them. */
    private String text(Cell cell, FormulaEvaluator evaluator) {
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim();
    }
}
