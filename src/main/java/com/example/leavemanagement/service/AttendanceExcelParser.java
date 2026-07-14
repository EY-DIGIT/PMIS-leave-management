package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.EmployeeAttendanceByDate;
import com.example.leavemanagement.exception.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
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
 * Reads an attendance spreadsheet into per-employee absence data for a given Attendance Start
 * Date / Attendance End Date period.
 *
 * <p>Each employee spans <b>3 rows</b> labelled (in column D) In-Time, Out-Time and
 * Total-Time. An employee block is located by its <b>In-Time</b> row — this tolerates
 * any number of title/header rows above the data and avoids drift, rather than
 * assuming a fixed header position or row stride. For each In-Time row:
 *
 * <ul>
 *   <li>Column A — Attendance ID, B — Employee Name, C — Designation.
 *   <li>Column D — the row label (In-Time / Out-Time / Total-Time).
 *   <li>Columns E.. — one column per day of the selected period, in order: column {@code
 *       LABEL_COL + n} is {@code startDate.plusDays(n - 1)}.
 * </ul>
 *
 * <p>The sheet must have exactly as many day columns as the period has days — see {@link
 * #detectColumnCount(Sheet)}.
 *
 * <p>A day where both the In-Time and Out-Time cells are 0/blank is recorded as an
 * absence for that employee.
 */
@Component
public class AttendanceExcelParser {

    private static final int COL_ATTENDANCE_ID = 0;
    private static final int COL_EMPLOYEE_NAME = 1;
    private static final int COL_DESIGNATION = 2;
    private static final int LABEL_COL = 3; // column D — first day column is LABEL_COL + 1
    private static final String IN_TIME = "intime";
    private static final String OUT_TIME = "outtime";

    private final DataFormatter formatter = new DataFormatter();

    public List<EmployeeAttendanceByDate> parse(MultipartFile file, LocalDate startDate, LocalDate endDate) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("An attendance Excel file is required (form field 'file')");
        }
        int expectedDays = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;

        List<EmployeeAttendanceByDate> result = new ArrayList<>();
        try (InputStream in = file.getInputStream();
                Workbook workbook = WorkbookFactory.create(in)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new BadRequestException("The workbook has no sheets");
            }

            int actualDays = detectColumnCount(sheet);
            if (actualDays != expectedDays) {
                throw new BadRequestException(
                        "Attendance sheet does not match the selected attendance period.\n"
                                + "Expected " + expectedDays + " days but found " + actualDays + " days.");
            }

            for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                Row inRow = sheet.getRow(r);
                if (inRow == null || !IN_TIME.equals(label(inRow))) {
                    continue; // only In-Time rows start an employee block
                }
                Row outRow = findLabelledRow(sheet, r + 1, OUT_TIME, 3);

                Set<LocalDate> absentDates = new LinkedHashSet<>();
                Map<LocalDate, Integer> workedMinutesByDate = new LinkedHashMap<>();
                for (int offset = 1; offset <= expectedDays; offset++) {
                    int col = LABEL_COL + offset;
                    LocalDate date = startDate.plusDays(offset - 1L);
                    Cell inCell = inRow.getCell(col);
                    Cell outCell = outRow == null ? null : outRow.getCell(col);
                    boolean inZero = isZero(inCell);
                    boolean outZero = isZero(outCell);
                    if (inZero && outZero) {
                        absentDates.add(date);
                    } else if (!inZero && !outZero) {
                        OptionalInt inMinutes = minutesOfDay(inCell);
                        OptionalInt outMinutes = minutesOfDay(outCell);
                        if (inMinutes.isPresent() && outMinutes.isPresent()) {
                            int worked = outMinutes.getAsInt() - inMinutes.getAsInt();
                            if (worked < 0) {
                                worked += 24 * 60; // clock wrapped past midnight
                            }
                            workedMinutesByDate.put(date, worked);
                        }
                    }
                }

                result.add(new EmployeeAttendanceByDate(
                        text(inRow.getCell(COL_ATTENDANCE_ID)),
                        text(inRow.getCell(COL_EMPLOYEE_NAME)),
                        text(inRow.getCell(COL_DESIGNATION)),
                        absentDates,
                        workedMinutesByDate));
            }
        } catch (IOException e) {
            throw new BadRequestException("Could not read the attendance file: " + e.getMessage());
        }

        if (result.isEmpty()) {
            throw new BadRequestException(
                    "The attendance file contains no employee rows (no 'In-Time' rows found in column D)");
        }
        return assignUniqueIds(result);
    }

    /**
     * Number of date columns present in the sheet, used to validate against the selected
     * period's day count. Determined by scanning each employee's In-Time row for the longest
     * contiguous run of non-blank cells starting right after the label column — the maximum
     * across all In-Time rows is taken, since any one employee's row may have trailing blank
     * cells for unrelated reasons (e.g. a rejoin partway through the period).
     */
    private int detectColumnCount(Sheet sheet) {
        int maxColumns = 0;
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || !IN_TIME.equals(label(row))) {
                continue;
            }
            maxColumns = Math.max(maxColumns, countTrailingNonBlank(row));
        }
        return maxColumns;
    }

    private int countTrailingNonBlank(Row row) {
        int count = 0;
        int col = LABEL_COL + 1;
        while (true) {
            Cell cell = row.getCell(col);
            if (cell == null || cell.getCellType() == CellType.BLANK) {
                break;
            }
            count++;
            col++;
        }
        return count;
    }

    /**
     * Ensures every employee has a unique id. Some exports mask the attendance id
     * (the same value, e.g. "XXXXXX", on every row), which would make resources
     * indistinguishable. When the ids are not all unique, they are replaced with
     * stable positional ids {@code R1, R2, …} (so the Nth resource lines up across
     * months); when they are already unique, the originals are kept.
     */
    private List<EmployeeAttendanceByDate> assignUniqueIds(List<EmployeeAttendanceByDate> employees) {
        long distinctNonBlank = employees.stream()
                .map(EmployeeAttendanceByDate::attendanceId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .count();
        if (distinctNonBlank == employees.size()) {
            return employees; // already unique
        }
        List<EmployeeAttendanceByDate> keyed = new ArrayList<>(employees.size());
        for (int i = 0; i < employees.size(); i++) {
            EmployeeAttendanceByDate e = employees.get(i);
            keyed.add(new EmployeeAttendanceByDate(
                    "R" + (i + 1), e.employeeName(), e.designation(), e.absentDates(), e.workedMinutesByDate()));
        }
        return keyed;
    }

    /** The normalised label in column D (e.g. "In-Time" -> "intime"). */
    private String label(Row row) {
        return text(row.getCell(LABEL_COL)).toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** Finds the first row at/after {@code startRow} (within {@code maxScan} rows) with the given label. */
    private Row findLabelledRow(Sheet sheet, int startRow, String normalizedLabel, int maxScan) {
        for (int r = startRow; r < startRow + maxScan && r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row != null && normalizedLabel.equals(label(row))) {
                return row;
            }
        }
        return null;
    }

    /** A cell counts as "no attendance" when blank, numeric 0, or a zero time string. */
    private boolean isZero(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return true;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue() == 0d;
        }
        String s = formatter.formatCellValue(cell).trim();
        return s.isEmpty() || s.equals("0") || s.equals("0:00") || s.equals("00:00");
    }

    /**
     * Reads a time-of-day cell as minutes since midnight. Handles both Excel time
     * cells (a fraction of a day) and {@code H:mm} / {@code HH:mm} text. Returns
     * empty when the cell can't be read as a time.
     */
    private OptionalInt minutesOfDay(Cell cell) {
        if (cell == null) {
            return OptionalInt.empty();
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            double fraction = value - Math.floor(value); // strip any date part
            return OptionalInt.of((int) Math.round(fraction * 24 * 60));
        }
        String s = formatter.formatCellValue(cell).trim();
        if (s.isEmpty()) {
            return OptionalInt.empty();
        }
        String[] parts = s.split(":");
        try {
            int hours = Integer.parseInt(parts[0].trim());
            int minutes = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            return OptionalInt.of(hours * 60 + minutes);
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private String text(Cell cell) {
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }
}
