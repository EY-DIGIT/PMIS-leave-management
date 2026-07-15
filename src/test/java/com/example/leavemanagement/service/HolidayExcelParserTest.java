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
    void parsesSNoHolidayDateDayRows() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Holidays");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("S.No");
            header.createCell(1).setCellValue("Holiday");
            header.createCell(2).setCellValue("Date");
            header.createCell(3).setCellValue("Day");

            // Row 1: "d MMMM" text date, day-of-week cross-check matches.
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(1);
            r1.createCell(1).setCellValue("Republic Day");
            r1.createCell(2).setCellValue("26 January");
            r1.createCell(3).setCellValue("Monday"); // 26-Jan-2026 is a Monday

            // Row 2: no day-of-week given (optional).
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue(2);
            r2.createCell(1).setCellValue("Independence Day");
            r2.createCell(2).setCellValue("15 August");

            // Row 3: fully blank -> skipped.
            sheet.createRow(3);

            List<HolidayItem> items = parser.parse(toFile(wb), 2026);

            assertThat(items)
                    .containsExactly(
                            new HolidayItem(LocalDate.of(2026, 1, 26), "Republic Day"),
                            new HolidayItem(LocalDate.of(2026, 8, 15), "Independence Day"));
        }
    }

    @Test
    void acceptsARealExcelDateCellInColumnC() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Holidays");
            sheet.createRow(0);

            CreationHelper helper = wb.getCreationHelper();
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
            Row r1 = sheet.createRow(1);
            r1.createCell(1).setCellValue("Republic Day");
            Cell dateCell = r1.createCell(2);
            dateCell.setCellValue(LocalDate.of(2026, 1, 26));
            dateCell.setCellStyle(dateStyle);

            List<HolidayItem> items = parser.parse(toFile(wb), 2026);

            assertThat(items).containsExactly(new HolidayItem(LocalDate.of(2026, 1, 26), "Republic Day"));
        }
    }

    @Test
    void rejectsWhenDayOfWeekColumnDoesNotMatchTheDate() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Holidays");
            sheet.createRow(0);
            Row r1 = sheet.createRow(1);
            r1.createCell(1).setCellValue("Republic Day");
            r1.createCell(2).setCellValue("26 January");
            r1.createCell(3).setCellValue("Tuesday"); // 26-Jan-2026 is actually a Monday

            assertThatThrownBy(() -> parser.parse(toFile(wb), 2026))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Row 2")
                    .hasMessageContaining("does not match");
        }
    }

    @Test
    void rejectsRowWithInvalidDate() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Holidays");
            sheet.createRow(0);
            Row r1 = sheet.createRow(1);
            r1.createCell(1).setCellValue("Some Holiday");
            r1.createCell(2).setCellValue("not-a-date");

            assertThatThrownBy(() -> parser.parse(toFile(wb), 2026))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Row 2")
                    .hasMessageContaining("invalid date");
        }
    }

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "holidays.xlsx", null, new byte[0]);
        assertThatThrownBy(() -> parser.parse(empty, 2026)).isInstanceOf(BadRequestException.class);
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
