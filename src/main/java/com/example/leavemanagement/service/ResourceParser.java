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
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Reads the resource master spreadsheet into rows ready to upsert.
 *
 * <p>Expected layout of the first sheet:
 *
 * <ul>
 *   <li>Row 1 — a header row (skipped).
 *   <li>Column A — res_id, B — name, C — emailId, D — rate_card, E — date_of_joining,
 *       F — last_date (optional), G — designationType, H — isactive (Yes/No).
 * </ul>
 *
 * Fully blank rows are ignored. Any malformed row raises a {@code 400}.
 */
@Component
public class ResourceParser {

    private static final int COL_RES_ID = 0;
    private static final int COL_NAME = 1;
    private static final int COL_EMAIL_ID = 2;
    private static final int COL_RATE_CARD = 3;
    private static final int COL_DATE_OF_JOINING = 4;
    private static final int COL_LAST_DATE = 5;
    private static final int COL_DESIGNATION_TYPE = 6;
    private static final int COL_IS_ACTIVE = 7;

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

            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue; // header row
                }
                if (isBlank(row.getCell(COL_RES_ID))) {
                    continue; // skip fully blank trailing rows
                }
                int humanRow = row.getRowNum() + 1;
                rows.add(new ResourceRow(
                        text(row.getCell(COL_RES_ID)),
                        readName(row.getCell(COL_NAME), humanRow),
                        text(row.getCell(COL_EMAIL_ID)),
                        readRateCard(row.getCell(COL_RATE_CARD), humanRow),
                        readDate(row.getCell(COL_DATE_OF_JOINING), humanRow, "date_of_joining", true),
                        readDate(row.getCell(COL_LAST_DATE), humanRow, "last_date", false),
                        text(row.getCell(COL_DESIGNATION_TYPE)),
                        readActive(row.getCell(COL_IS_ACTIVE))));
            }
        } catch (IOException e) {
            throw new BadRequestException("Could not read the Excel file: " + e.getMessage());
        }

        if (rows.isEmpty()) {
            throw new BadRequestException("The Excel file contains no resource rows");
        }
        return rows;
    }

    private String readName(Cell cell, int humanRow) {
        String name = text(cell);
        if (name.isEmpty()) {
            throw new BadRequestException("Row %d: name (column B) is missing".formatted(humanRow));
        }
        return name;
    }

    private Double readRateCard(Cell cell, int humanRow) {
        if (isBlank(cell)) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        String text = text(cell);
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new BadRequestException(
                    "Row %d: invalid rate_card '%s' (column D)".formatted(humanRow, text));
        }
    }

    private LocalDate readDate(Cell cell, int humanRow, String columnLabel, boolean required) {
        if (isBlank(cell)) {
            if (required) {
                throw new BadRequestException("Row %d: %s is missing".formatted(humanRow, columnLabel));
            }
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String text = text(cell);
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(
                    "Row %d: invalid %s '%s' (expected an Excel date or yyyy-MM-dd)"
                            .formatted(humanRow, columnLabel, text));
        }
    }

    private boolean readActive(Cell cell) {
        String text = text(cell);
        return text.equalsIgnoreCase("yes") || text.equalsIgnoreCase("true") || text.equals("1");
    }

    private boolean isBlank(Cell cell) {
        return cell == null
                || cell.getCellType() == CellType.BLANK
                || (cell.getCellType() == CellType.STRING
                        && cell.getStringCellValue().trim().isEmpty());
    }

    private String text(Cell cell) {
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }
}
