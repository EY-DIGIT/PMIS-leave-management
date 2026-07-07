package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.leavemanagement.dto.EmployeeAttendance;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class AttendanceExcelParserTest {

    private static final int MILESTONE_COL = 3;
    private static final int LABEL_COL = 4; // day d lives in column LABEL_COL + d

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
            header.createCell(MILESTONE_COL).setCellValue("Milestone ID");
            header.createCell(LABEL_COL).setCellValue("Date");

            Row in = sheet.createRow(2);
            Row out = sheet.createRow(3);
            Row total = sheet.createRow(4); // Total-Time row (ignored)

            in.createCell(0).setCellValue("E1");
            in.createCell(1).setCellValue("Asha");
            in.createCell(2).setCellValue("Dev");
            in.createCell(MILESTONE_COL).setCellValue("M1");
            in.createCell(LABEL_COL).setCellValue("In-Time");
            out.createCell(LABEL_COL).setCellValue("Out-Time");
            total.createCell(LABEL_COL).setCellValue("Total-Time");

            // Day 1: 10:00 -> 18:30 = 8h30 (510 min).
            in.createCell(LABEL_COL + 1).setCellValue("10:00");
            out.createCell(LABEL_COL + 1).setCellValue("18:30");
            // Day 2: 09:15 -> 16:15 = 7h00 (420 min, a short day).
            in.createCell(LABEL_COL + 2).setCellValue("9:15");
            out.createCell(LABEL_COL + 2).setCellValue("16:15");
            // Day 3: both zero -> absent.
            in.createCell(LABEL_COL + 3).setCellValue("0");
            out.createCell(LABEL_COL + 3).setCellValue("0");

            List<EmployeeAttendance> result = parser.parse(toFile(wb));

            assertThat(result).hasSize(1); // title + header rows are not mistaken for employees
            EmployeeAttendance e = result.get(0);
            assertThat(e.attendanceId()).isEqualTo("E1");
            assertThat(e.milestoneId()).isEqualTo("M1");
            assertThat(e.workedMinutesByDay()).containsEntry(1, 510).containsEntry(2, 420);
            assertThat(e.absentDays()).contains(3);
            assertThat(e.absentDays()).doesNotContain(1, 2);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void assignsPositionalIdsWhenAttendanceIdsAreMasked() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Attendance");
            sheet.createRow(0).createCell(0).setCellValue("title");
            // Two employees, both with the same masked id "XXXXXX".
            addEmployeeBlock(sheet, 1, "XXXXXX", "Dev");
            addEmployeeBlock(sheet, 4, "XXXXXX", "QA");

            List<EmployeeAttendance> result = parser.parse(toFile(wb));

            assertThat(result).hasSize(2);
            assertThat(result).extracting(EmployeeAttendance::attendanceId).containsExactly("R1", "R2");
            assertThat(result).extracting(EmployeeAttendance::designation).containsExactly("Dev", "QA");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void addEmployeeBlock(Sheet sheet, int startRow, String id, String designation) {
        Row in = sheet.createRow(startRow);
        Row out = sheet.createRow(startRow + 1);
        sheet.createRow(startRow + 2);
        in.createCell(0).setCellValue(id);
        in.createCell(2).setCellValue(designation);
        in.createCell(LABEL_COL).setCellValue("In-Time");
        // no milestone id set here — the masked-id test doesn't exercise it
        out.createCell(LABEL_COL).setCellValue("Out-Time");
        sheet.getRow(startRow + 2).createCell(LABEL_COL).setCellValue("Total-Time");
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
