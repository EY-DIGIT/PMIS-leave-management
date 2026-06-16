package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.HolidayItem;
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
 * Reads public holidays from an uploaded spreadsheet (.xlsx or .xls).
 *
 * <p>Expected layout of the first sheet:
 *
 * <ul>
 *   <li>Row 1 — a header row (skipped).
 *   <li>Column A — the holiday date (an Excel date cell, or text {@code yyyy-MM-dd}).
 *   <li>Column B — the holiday name.
 * </ul>
 *
 * Fully blank rows are ignored. Any malformed row raises a {@code 400}.
 */
@Component
public class HolidayExcelParser {

    private final DataFormatter formatter = new DataFormatter();

    public List<HolidayItem> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("An Excel file is required (form field 'file')");
        }

        List<HolidayItem> items = new ArrayList<>();
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
                Cell dateCell = row.getCell(0);
                Cell nameCell = row.getCell(1);
                if (isBlank(dateCell) && isBlank(nameCell)) {
                    continue; // skip fully blank rows
                }
                int humanRow = row.getRowNum() + 1;
                items.add(new HolidayItem(readDate(dateCell, humanRow), readName(nameCell, humanRow)));
            }
        } catch (IOException e) {
            throw new BadRequestException("Could not read the Excel file: " + e.getMessage());
        }

        if (items.isEmpty()) {
            throw new BadRequestException("The Excel file contains no holiday rows");
        }
        return items;
    }

    private LocalDate readDate(Cell cell, int humanRow) {
        if (isBlank(cell)) {
            throw new BadRequestException("Row %d: date (column A) is missing".formatted(humanRow));
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String text = formatter.formatCellValue(cell).trim();
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(
                    "Row %d: invalid date '%s' (expected an Excel date or yyyy-MM-dd)".formatted(humanRow, text));
        }
    }

    private String readName(Cell cell, int humanRow) {
        if (isBlank(cell)) {
            throw new BadRequestException("Row %d: name (column B) is missing".formatted(humanRow));
        }
        String name = formatter.formatCellValue(cell).trim();
        if (name.isEmpty()) {
            throw new BadRequestException("Row %d: name (column B) is blank".formatted(humanRow));
        }
        return name;
    }

    private boolean isBlank(Cell cell) {
        return cell == null
                || cell.getCellType() == CellType.BLANK
                || (cell.getCellType() == CellType.STRING
                        && cell.getStringCellValue().trim().isEmpty());
    }
}
