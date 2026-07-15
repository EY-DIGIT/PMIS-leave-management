package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.HolidayItem;
import com.example.leavemanagement.exception.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 *   <li>Column A — S.No (ignored).
 *   <li>Column B — the holiday name.
 *   <li>Column C — the date, day and month only, no year (e.g. "26 January"), or an Excel date
 *       cell. Text dates are resolved against the upload's {@code year}.
 *   <li>Column D — the day of week (e.g. "Monday"), optional — cross-checked against the actual
 *       day of week for column C's date if present, to catch a wrong year/date at upload time.
 * </ul>
 *
 * Fully blank rows are ignored. Any malformed row raises a {@code 400}.
 */
@Component
public class HolidayExcelParser {

    private static final int COL_NAME = 1;
    private static final int COL_DATE = 2;
    private static final int COL_DAY = 3;
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH);

    private final DataFormatter formatter = new DataFormatter();

    public List<HolidayItem> parse(MultipartFile file, int year) {
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
                Cell nameCell = row.getCell(COL_NAME);
                Cell dateCell = row.getCell(COL_DATE);
                if (isBlank(nameCell) && isBlank(dateCell)) {
                    continue; // skip fully blank rows
                }
                int humanRow = row.getRowNum() + 1;
                LocalDate date = readDate(dateCell, year, humanRow);
                validateDayOfWeek(row.getCell(COL_DAY), date, humanRow);
                items.add(new HolidayItem(date, readName(nameCell, humanRow)));
            }
        } catch (IOException e) {
            throw new BadRequestException("Could not read the Excel file: " + e.getMessage());
        }

        if (items.isEmpty()) {
            throw new BadRequestException("The Excel file contains no holiday rows");
        }
        return items;
    }

    /**
     * Column C: an Excel date cell (used as-is, but must fall in {@code year}), or day+month text
     * like "26 January" (resolved against {@code year}).
     */
    private LocalDate readDate(Cell cell, int year, int humanRow) {
        if (isBlank(cell)) {
            throw new BadRequestException("Row %d: date (column C) is missing".formatted(humanRow));
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            LocalDate date = cell.getLocalDateTimeCellValue().toLocalDate();
            if (date.getYear() != year) {
                throw new BadRequestException(
                        "Row %d: date %s is not within year %d".formatted(humanRow, date, year));
            }
            return date;
        }
        String text = formatter.formatCellValue(cell).trim();
        try {
            return LocalDate.parse(text); // yyyy-MM-dd, still accepted
        } catch (DateTimeParseException ignored) {
            // fall through to day+month parsing below
        }
        try {
            return MonthDay.parse(text, DAY_MONTH).atYear(year);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(
                    ("Row %d: invalid date '%s' (expected an Excel date, yyyy-MM-dd, or 'd MMMM' e.g. "
                                    + "'26 January')")
                            .formatted(humanRow, text));
        }
    }

    /** Column D is optional; when present it must match the actual day of week for column C's date. */
    private void validateDayOfWeek(Cell cell, LocalDate date, int humanRow) {
        if (isBlank(cell)) {
            return;
        }
        String text = formatter.formatCellValue(cell).trim();
        DayOfWeek expected = date.getDayOfWeek();
        if (!expected.getDisplayName(TextStyle.FULL, Locale.ENGLISH).equalsIgnoreCase(text)) {
            throw new BadRequestException(
                    ("Row %d: day '%s' does not match %s, which is a %s — check the date and year.")
                            .formatted(
                                    humanRow, text, date, expected.getDisplayName(TextStyle.FULL, Locale.ENGLISH)));
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
