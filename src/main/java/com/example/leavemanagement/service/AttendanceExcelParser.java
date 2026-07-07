package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.EmployeeAttendance;
import com.example.leavemanagement.exception.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
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
 * Reads a monthly attendance spreadsheet into per-employee absence data.
 *
 * <p>Each employee spans <b>3 rows</b> labelled (in column D) In-Time, Out-Time and
 * Total-Time. An employee block is located by its <b>In-Time</b> row — this tolerates
 * any number of title/header rows above the data and avoids drift, rather than
 * assuming a fixed header position or row stride. For each In-Time row:
 *
 * <ul>
 *   <li>Column A — Attendance ID, B — Employee Name, C — Designation, D — Milestone ID.
 *   <li>Column E — the row label (In-Time / Out-Time / Total-Time).
 *   <li>Columns F.. — day-of-month 1..31 (day {@code d} is column index {@code 4 + d}).
 * </ul>
 *
 * A day where both the In-Time and Out-Time cells are 0/blank is recorded as an
 * absence for that employee.
 */
@Component
public class AttendanceExcelParser {

    private static final int COL_ATTENDANCE_ID = 0;
    private static final int COL_EMPLOYEE_NAME = 1;
    private static final int COL_DESIGNATION = 2;
    private static final int COL_MILESTONE_ID = 3;
    private static final int LABEL_COL = 4; // column E — first day column is LABEL_COL + 1
    private static final int MAX_DAY = 31;
    private static final String IN_TIME = "intime";
    private static final String OUT_TIME = "outtime";

    private final DataFormatter formatter = new DataFormatter();

    public List<EmployeeAttendance> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("An attendance Excel file is required (form field 'file')");
        }

        List<EmployeeAttendance> result = new ArrayList<>();
        try (InputStream in = file.getInputStream();
                Workbook workbook = WorkbookFactory.create(in)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new BadRequestException("The workbook has no sheets");
            }

            for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                Row inRow = sheet.getRow(r);
                if (inRow == null || !IN_TIME.equals(label(inRow))) {
                    continue; // only In-Time rows start an employee block
                }
                Row outRow = findLabelledRow(sheet, r + 1, OUT_TIME, 3);

                Set<Integer> absentDays = new LinkedHashSet<>();
                Map<Integer, Integer> workedMinutesByDay = new LinkedHashMap<>();
                for (int day = 1; day <= MAX_DAY; day++) {
                    int col = LABEL_COL + day;
                    Cell inCell = inRow.getCell(col);
                    Cell outCell = outRow == null ? null : outRow.getCell(col);
                    boolean inZero = isZero(inCell);
                    boolean outZero = isZero(outCell);
                    if (inZero && outZero) {
                        absentDays.add(day);
                    } else if (!inZero && !outZero) {
                        OptionalInt inMinutes = minutesOfDay(inCell);
                        OptionalInt outMinutes = minutesOfDay(outCell);
                        if (inMinutes.isPresent() && outMinutes.isPresent()) {
                            int worked = outMinutes.getAsInt() - inMinutes.getAsInt();
                            if (worked < 0) {
                                worked += 24 * 60; // clock wrapped past midnight
                            }
                            workedMinutesByDay.put(day, worked);
                        }
                    }
                }

                result.add(new EmployeeAttendance(
                        text(inRow.getCell(COL_ATTENDANCE_ID)),
                        text(inRow.getCell(COL_EMPLOYEE_NAME)),
                        text(inRow.getCell(COL_DESIGNATION)),
                        text(inRow.getCell(COL_MILESTONE_ID)),
                        absentDays,
                        workedMinutesByDay));
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
     * Ensures every employee has a unique id. Some exports mask the attendance id
     * (the same value, e.g. "XXXXXX", on every row), which would make resources
     * indistinguishable. When the ids are not all unique, they are replaced with
     * stable positional ids {@code R1, R2, …} (so the Nth resource lines up across
     * months); when they are already unique, the originals are kept.
     */
    private List<EmployeeAttendance> assignUniqueIds(List<EmployeeAttendance> employees) {
        long distinctNonBlank = employees.stream()
                .map(EmployeeAttendance::attendanceId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .count();
        if (distinctNonBlank == employees.size()) {
            return employees; // already unique
        }
        List<EmployeeAttendance> keyed = new ArrayList<>(employees.size());
        for (int i = 0; i < employees.size(); i++) {
            EmployeeAttendance e = employees.get(i);
            keyed.add(new EmployeeAttendance(
                    "R" + (i + 1),
                    e.employeeName(),
                    e.designation(),
                    e.milestoneId(),
                    e.absentDays(),
                    e.workedMinutesByDay()));
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
