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

    @Test
    void parsesActiveAndRelievedRowsWithRateCardByYear() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Resources");

            // Row 0: merged group headings (content doesn't matter to the parser, just skipped).
            Row group = sheet.createRow(0);
            group.createCell(0).setCellValue("Resource details");
            group.createCell(6).setCellValue("Rate");
            group.createCell(13).setCellValue("Category");

            // Row 1: column labels (also skipped).
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("Attendance ID");
            header.createCell(1).setCellValue("Employee Name");
            header.createCell(2).setCellValue("Role as per Contract");
            header.createCell(3).setCellValue("Location");
            header.createCell(4).setCellValue("Date of Joining");
            header.createCell(5).setCellValue("Last Day of Working");
            for (int i = 0; i < 7; i++) {
                header.createCell(6 + i).setCellValue("Year-" + (i + 1));
            }
            header.createCell(13).setCellValue("Category");
            header.createCell(14).setCellValue("CCN / ASG details");

            // Row 2: an active resource (blank Last Day of Working), full 7-year rate card.
            Row r1 = sheet.createRow(2);
            r1.createCell(0).setCellValue("1");
            r1.createCell(1).setCellValue("Sanju");
            r1.createCell(2).setCellValue("Security Crypto Lead");
            r1.createCell(3).setCellValue("Bengaluru");
            r1.createCell(4).setCellValue("2026-01-01");
            r1.createCell(6).setCellValue(100874);
            r1.createCell(7).setCellValue(107594);
            r1.createCell(8).setCellValue(116465);
            r1.createCell(9).setCellValue(122862);
            r1.createCell(10).setCellValue(129707);
            r1.createCell(11).setCellValue(137031);
            r1.createCell(12).setCellValue(144868);
            r1.createCell(13).setCellValue("RFP");
            r1.createCell(14).setCellValue("NA");

            // Row 3: a relieved resource (Last Day of Working set), only 2 years of rates.
            Row r2 = sheet.createRow(3);
            r2.createCell(0).setCellValue("4");
            r2.createCell(1).setCellValue("Shreyas");
            r2.createCell(2).setCellValue("Build and Release Engineer");
            r2.createCell(3).setCellValue("Bengaluru");
            r2.createCell(4).setCellValue("2026-01-01");
            r2.createCell(5).setCellValue("2026-06-30");
            r2.createCell(6).setCellValue(127481);
            r2.createCell(7).setCellValue(136064);
            r2.createCell(13).setCellValue("CCN");
            r2.createCell(14).setCellValue("CCN001");

            // trailing blank row should be skipped
            sheet.createRow(4);

            List<ResourceRow> rows = parser.parse(toFile(wb));

            assertThat(rows).hasSize(2);
            ResourceRow active = rows.get(0);
            assertThat(active.resId()).isEqualTo("1");
            assertThat(active.name()).isEqualTo("Sanju");
            assertThat(active.role()).isEqualTo("Security Crypto Lead");
            assertThat(active.location()).isEqualTo("Bengaluru");
            assertThat(active.dateOfJoining()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(active.lastDayOfWorking()).isNull();
            assertThat(active.active()).isTrue();
            assertThat(active.rateCardByYear())
                    .hasSize(7)
                    .containsEntry("Year-1", 100874d)
                    .containsEntry("Year-7", 144868d);
            assertThat(active.category()).isEqualTo("RFP");
            assertThat(active.categoryDetails()).isEqualTo("NA");

            ResourceRow relieved = rows.get(1);
            assertThat(relieved.resId()).isEqualTo("4");
            assertThat(relieved.lastDayOfWorking()).isEqualTo(LocalDate.of(2026, 6, 30));
            assertThat(relieved.active()).isFalse();
            assertThat(relieved.rateCardByYear()).hasSize(2).containsEntry("Year-1", 127481d);
            assertThat(relieved.category()).isEqualTo("CCN");
            assertThat(relieved.categoryDetails()).isEqualTo("CCN001");
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
