package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.leavemanagement.dto.HolidayItem;
import com.example.leavemanagement.exception.BadRequestException;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class HolidayExcelParserTest {

    private final HolidayExcelParser parser = new HolidayExcelParser();

    @Test
    void parsesDateAndTextRows() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Holidays");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("date");
            header.createCell(1).setCellValue("name");

            // Row 1: a real Excel date cell.
            CreationHelper helper = wb.getCreationHelper();
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
            Row r1 = sheet.createRow(1);
            Cell d1 = r1.createCell(0);
            d1.setCellValue(LocalDate.of(2026, 1, 26));
            d1.setCellStyle(dateStyle);
            r1.createCell(1).setCellValue("Republic Day");

            // Row 2: a text date "yyyy-MM-dd".
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("2026-08-15");
            r2.createCell(1).setCellValue("Independence Day");

            // Row 3: fully blank -> skipped.
            sheet.createRow(3);

            List<HolidayItem> items = parser.parse(toFile(wb));

            assertThat(items)
                    .containsExactly(
                            new HolidayItem(LocalDate.of(2026, 1, 26), "Republic Day"),
                            new HolidayItem(LocalDate.of(2026, 8, 15), "Independence Day"));
        }
    }

    @Test
    void rejectsRowWithInvalidDate() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Holidays");
            sheet.createRow(0).createCell(0).setCellValue("date");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("not-a-date");
            r1.createCell(1).setCellValue("Some Holiday");

            assertThatThrownBy(() -> parser.parse(toFile(wb)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Row 2")
                    .hasMessageContaining("invalid date");
        }
    }

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "holidays.xlsx", null, new byte[0]);
        assertThatThrownBy(() -> parser.parse(empty)).isInstanceOf(BadRequestException.class);
    }

    private MockMultipartFile toFile(XSSFWorkbook wb) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return new MockMultipartFile(
                    "file",
                    "holidays.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        }
    }
}
