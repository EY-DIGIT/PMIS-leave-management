package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.leavemanagement.dto.ResourceRow;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ResourceParserTest {

    private final ResourceParser parser = new ResourceParser();

    /**
     * New column layout (HEADER_ROWS = 1, no year-rate columns):
     * A=Attendance ID, B=Employee Name, C=Role, D=Location,
     * E=Date of Joining, F=Last Day of Working, G=Category, H=CCN/ASG Details
     */
    @Test
    void parsesActiveAndRelievedRows() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Resources");

            // Row 0: column labels — 1 header row, skipped by HEADER_ROWS = 1.
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Attendance ID");
            header.createCell(1).setCellValue("Employee Name");
            header.createCell(2).setCellValue("Role as per Contract");
            header.createCell(3).setCellValue("Location");
            header.createCell(4).setCellValue("Date of Joining");
            header.createCell(5).setCellValue("Last Day of Working");
            header.createCell(6).setCellValue("Category (RFP/CCN/ASG)");
            header.createCell(7).setCellValue("CCN/ASG Details");

            // Row 1: an active resource (blank Last Day of Working).
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("421");
            r1.createCell(1).setCellValue("Sanju");
            r1.createCell(2).setCellValue("Security Crypto Lead");
            r1.createCell(3).setCellValue("Chennai");
            r1.createCell(4).setCellValue("2026-01-01");
            // column 5 blank (no last day)
            r1.createCell(6).setCellValue("RFP");
            r1.createCell(7).setCellValue("NA");

            // Row 2: a relieved resource (Last Day of Working set).
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("424");
            r2.createCell(1).setCellValue("Shreyas");
            r2.createCell(2).setCellValue("Build and Release Engineer");
            r2.createCell(3).setCellValue("Bengaluru");
            r2.createCell(4).setCellValue("2026-01-01");
            r2.createCell(5).setCellValue("2026-06-30");
            r2.createCell(6).setCellValue("CCN");
            r2.createCell(7).setCellValue("CCN001");

            // trailing blank row — should be skipped
            sheet.createRow(3);

            List<ResourceRow> rows = parser.parse(toFile(wb));

            assertThat(rows).hasSize(2);

            ResourceRow active = rows.get(0);
            assertThat(active.resId()).isEqualTo("421");
            assertThat(active.name()).isEqualTo("Sanju");
            assertThat(active.role()).isEqualTo("Security Crypto Lead");
            assertThat(active.location()).isEqualTo("Chennai");
            assertThat(active.dateOfJoining()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(active.lastDayOfWorking()).isNull();
            assertThat(active.active()).isTrue();
            assertThat(active.category()).isEqualTo("RFP");
            assertThat(active.categoryDetails()).isEqualTo("NA");

            ResourceRow relieved = rows.get(1);
            assertThat(relieved.resId()).isEqualTo("424");
            assertThat(relieved.lastDayOfWorking()).isEqualTo(LocalDate.of(2026, 6, 30));
            assertThat(relieved.active()).isFalse();
            assertThat(relieved.category()).isEqualTo("CCN");
            assertThat(relieved.categoryDetails()).isEqualTo("CCN001");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /** Date cells exported from Excel often contain "2026-01-01 0:00:00" — strip the time part. */
    @Test
    void stripsTimeSuffixFromDateStrings() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Resources");
            sheet.createRow(0).createCell(0).setCellValue("Attendance ID"); // header

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("421");
            r1.createCell(1).setCellValue("Sanju");
            r1.createCell(2).setCellValue("Security Crypto Lead");
            r1.createCell(3).setCellValue("Chennai");
            r1.createCell(4).setCellValue("2026-01-01 0:00:00"); // date with time suffix

            List<ResourceRow> rows = parser.parse(toFile(wb));
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).dateOfJoining()).isEqualTo(LocalDate.of(2026, 1, 1));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void resolvesAutofilledFormulaCellsToTheirCalculatedResultNotFormulaText() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Resources");
            sheet.createRow(0).createCell(0).setCellValue("Attendance ID"); // 1 header row

            // Row 1: literal seed value.
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(1);
            r1.createCell(1).setCellValue("Sanju");
            r1.createCell(4).setCellValue("2026-01-01");

            // Row 2: Excel autofill drags down "=A2+1" rather than a literal value.
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellFormula("A2+1");
            r2.createCell(1).setCellValue("Subhman");
            r2.createCell(4).setCellValue("2026-01-01");

            List<ResourceRow> rows = parser.parse(toFile(wb));

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).resId()).isEqualTo("1");
            assertThat(rows.get(1).resId()).isEqualTo("2");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private MockMultipartFile toFile(Workbook wb) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return new MockMultipartFile(
                    "file",
                    "resources.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        }
    }
}
