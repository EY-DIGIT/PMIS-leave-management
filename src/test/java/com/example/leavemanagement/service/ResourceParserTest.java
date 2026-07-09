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
    void parsesActiveAndInactiveRows() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Resources");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("res_id");
            header.createCell(1).setCellValue("name");
            header.createCell(2).setCellValue("emailId");
            header.createCell(3).setCellValue("rate_card");
            header.createCell(4).setCellValue("date_of_joining");
            header.createCell(5).setCellValue("last_date");
            header.createCell(6).setCellValue("designationType");
            header.createCell(7).setCellValue("isactive");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("100001");
            r1.createCell(1).setCellValue("Saurabh Pandey");
            r1.createCell(2).setCellValue("saurabh.pandey@example.com");
            r1.createCell(3).setCellValue(60000);
            r1.createCell(4).setCellValue("2026-01-06");
            r1.createCell(6).setCellValue("Software Engineer");
            r1.createCell(7).setCellValue("Yes");

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("100005");
            r2.createCell(1).setCellValue("Priya Nair");
            r2.createCell(2).setCellValue("priya.nair@example.com");
            r2.createCell(3).setCellValue(80000);
            r2.createCell(4).setCellValue("2026-02-23");
            r2.createCell(5).setCellValue("2026-06-23");
            r2.createCell(6).setCellValue("Business Analyst");
            r2.createCell(7).setCellValue("No");

            // trailing blank row should be skipped
            sheet.createRow(3);

            List<ResourceRow> rows = parser.parse(toFile(wb));

            assertThat(rows).hasSize(2);
            ResourceRow active = rows.get(0);
            assertThat(active.resId()).isEqualTo("100001");
            assertThat(active.rateCard()).isEqualTo(60000d);
            assertThat(active.dateOfJoining()).isEqualTo(LocalDate.of(2026, 1, 6));
            assertThat(active.lastDate()).isNull();
            assertThat(active.active()).isTrue();

            ResourceRow inactive = rows.get(1);
            assertThat(inactive.resId()).isEqualTo("100005");
            assertThat(inactive.lastDate()).isEqualTo(LocalDate.of(2026, 6, 23));
            assertThat(inactive.active()).isFalse();
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
