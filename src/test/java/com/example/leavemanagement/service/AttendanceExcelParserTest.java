package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.leavemanagement.dto.EmployeeAttendanceByDate;
import com.example.leavemanagement.exception.BadRequestException;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class AttendanceExcelParserTest {

    private static final int LABEL_COL = 3; // day n lives in column LABEL_COL + n

    private final AttendanceExcelParser parser = new AttendanceExcelParser();

    @Test
    void computesWorkedMinutesFromInAndOutTimesAndFlagsAbsence() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Attendance");
            // A title row (row 0) above the column header (row 1) — like the real AeBAS export.
            sheet.createRow(0).createCell(0).setCellValue("AeBAS Monthly report ...");
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("Attendance ID");
            header.createCell(1).setCellValue("Employee Name");
            header.createCell(2).setCellValue("Designation");
            header.createCell(LABEL_COL).setCellValue("Date");

            Row in = sheet.createRow(2);
            Row out = sheet.createRow(3);
            Row total = sheet.createRow(4); // Total-Time row (ignored)

            in.createCell(0).setCellValue("E1");
            in.createCell(1).setCellValue("Asha");
            in.createCell(2).setCellValue("Dev");
            in.createCell(LABEL_COL).setCellValue("In-Time");
            out.createCell(LABEL_COL).setCellValue("Out-Time");
            total.createCell(LABEL_COL).setCellValue("Total-Time");

            // Day 1 (01-Jun): 10:00 -> 18:30 = 8h30 (510 min).
            in.createCell(LABEL_COL + 1).setCellValue("10:00");
            out.createCell(LABEL_COL + 1).setCellValue("18:30");
            // Day 2 (02-Jun): 09:15 -> 16:15 = 7h00 (420 min, a short day).
            in.createCell(LABEL_COL + 2).setCellValue("9:15");
            out.createCell(LABEL_COL + 2).setCellValue("16:15");
            // Day 3 (03-Jun): both zero -> absent.
            in.createCell(LABEL_COL + 3).setCellValue("0");
            out.createCell(LABEL_COL + 3).setCellValue("0");

            LocalDate startDate = LocalDate.of(2026, 6, 1);
            LocalDate endDate = LocalDate.of(2026, 6, 3);
            List<EmployeeAttendanceByDate> result = parser.parse(toFile(wb), startDate, endDate);

            assertThat(result).hasSize(1); // title + header rows are not mistaken for employees
            EmployeeAttendanceByDate e = result.get(0);
            assertThat(e.attendanceId()).isEqualTo("E1");
            assertThat(e.workedMinutesByDate())
                    .containsEntry(LocalDate.of(2026, 6, 1), 510)
                    .containsEntry(LocalDate.of(2026, 6, 2), 420);
            assertThat(e.absentDates()).contains(LocalDate.of(2026, 6, 3));
            assertThat(e.absentDates()).doesNotContain(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void assignsPositionalIdsWhenAttendanceIdsAreMasked() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Attendance");
            sheet.createRow(0).createCell(0).setCellValue("title");
            // Two employees, both with the same masked id "XXXXXX", 3 day columns each.
            addEmployeeBlock(sheet, 1, "XXXXXX", "Dev");
            addEmployeeBlock(sheet, 4, "XXXXXX", "QA");

            LocalDate startDate = LocalDate.of(2026, 6, 1);
            LocalDate endDate = LocalDate.of(2026, 6, 3);
            List<EmployeeAttendanceByDate> result = parser.parse(toFile(wb), startDate, endDate);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(EmployeeAttendanceByDate::attendanceId).containsExactly("R1", "R2");
            assertThat(result).extracting(EmployeeAttendanceByDate::designation).containsExactly("Dev", "QA");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void rejectsSheetWhoseColumnCountDoesNotMatchThePeriod() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Attendance");
            sheet.createRow(0).createCell(0).setCellValue("title");
            addEmployeeBlock(sheet, 1, "E1", "Dev"); // only 3 day columns

            LocalDate startDate = LocalDate.of(2026, 7, 1);
            LocalDate endDate = LocalDate.of(2026, 7, 31); // expects 31 columns

            assertThatThrownBy(() -> parser.parse(toFile(wb), startDate, endDate))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Attendance sheet does not match the selected attendance period.\n"
                            + "Expected 31 days but found 3 days.");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void supportsPartialMonthPeriod() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Attendance");
            sheet.createRow(0).createCell(0).setCellValue("title");
            // 26 day columns for 05-Jul-2026 to 30-Jul-2026.
            addEmployeeBlock(sheet, 1, "E1", "Dev", 26);

            LocalDate startDate = LocalDate.of(2026, 7, 5);
            LocalDate endDate = LocalDate.of(2026, 7, 30);
            List<EmployeeAttendanceByDate> result = parser.parse(toFile(wb), startDate, endDate);

            assertThat(result).hasSize(1);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void supportsCrossMonthPeriod() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Attendance");
            sheet.createRow(0).createCell(0).setCellValue("title");
            // 25-Jul-2026 to 24-Aug-2026 -> 31 day columns.
            addEmployeeBlock(sheet, 1, "E1", "Dev", 31);

            LocalDate startDate = LocalDate.of(2026, 7, 25);
            LocalDate endDate = LocalDate.of(2026, 8, 24);
            List<EmployeeAttendanceByDate> result = parser.parse(toFile(wb), startDate, endDate);

            EmployeeAttendanceByDate e = result.get(0);
            // last column (offset 31) should land on 24-Aug-2026.
            assertThat(e.absentDates()).contains(LocalDate.of(2026, 8, 24));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void treatsNonWorkingStatusLabelsAsAbsence() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Attendance");
            sheet.createRow(0).createCell(0).setCellValue("title");

            Row in = sheet.createRow(1);
            Row out = sheet.createRow(2);
            Row total = sheet.createRow(3);

            in.createCell(0).setCellValue("E1");
            in.createCell(2).setCellValue("Dev");
            in.createCell(LABEL_COL).setCellValue("In-Time");
            out.createCell(LABEL_COL).setCellValue("Out-Time");
            total.createCell(LABEL_COL).setCellValue("Total-Time");

            // Day 1: WO (Weekend Off) label — should be treated as absent
            in.createCell(LABEL_COL + 1).setCellValue("WO");
            out.createCell(LABEL_COL + 1).setCellValue("WO");
            // Day 2: PL (Paid Leave) label
            in.createCell(LABEL_COL + 2).setCellValue("PL");
            out.createCell(LABEL_COL + 2).setCellValue("PL");
            // Day 3: UL (Unpaid Leave) label
            in.createCell(LABEL_COL + 3).setCellValue("UL");
            out.createCell(LABEL_COL + 3).setCellValue("UL");

            LocalDate startDate = LocalDate.of(2026, 6, 1);
            LocalDate endDate = LocalDate.of(2026, 6, 3);
            List<EmployeeAttendanceByDate> result = parser.parse(toFile(wb), startDate, endDate);

            EmployeeAttendanceByDate e = result.get(0);
            assertThat(e.absentDates()).containsExactlyInAnyOrder(
                    LocalDate.of(2026, 6, 1),
                    LocalDate.of(2026, 6, 2),
                    LocalDate.of(2026, 6, 3));
            assertThat(e.workedMinutesByDate()).isEmpty();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void addEmployeeBlock(Sheet sheet, int startRow, String id, String designation) {
        addEmployeeBlock(sheet, startRow, id, designation, 3);
    }

    private void addEmployeeBlock(Sheet sheet, int startRow, String id, String designation, int dayColumns) {
        Row in = sheet.createRow(startRow);
        Row out = sheet.createRow(startRow + 1);
        sheet.createRow(startRow + 2);
        in.createCell(0).setCellValue(id);
        in.createCell(2).setCellValue(designation);
        in.createCell(LABEL_COL).setCellValue("In-Time");
        out.createCell(LABEL_COL).setCellValue("Out-Time");
        sheet.getRow(startRow + 2).createCell(LABEL_COL).setCellValue("Total-Time");
        for (int day = 1; day <= dayColumns; day++) {
            in.createCell(LABEL_COL + day).setCellValue("0");
            out.createCell(LABEL_COL + day).setCellValue("0");
        }
    }

    private MockMultipartFile toFile(XSSFWorkbook wb) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return new MockMultipartFile(
                    "file",
                    "attendance.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        }
    }
}
