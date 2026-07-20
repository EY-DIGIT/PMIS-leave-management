package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.HolidayItem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Generates an Excel workbook from a list of {@link HolidayItem}s, matching the
 * format that {@code HolidayExcelParser} can re-import: header row + [S.No, Holiday, Date, Day].
 */
@Component
public class HolidayExcelExporter {

    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH); // "Monday"
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMMM", Locale.ENGLISH); // "26 January"

    public byte[] export(int year, List<HolidayItem> holidays) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Holidays " + year);

            CellStyle headerStyle = headerStyle(wb);

            // Header row
            Row header = sheet.createRow(0);
            styledCell(header, 0, "S.No", headerStyle);
            styledCell(header, 1, "Holiday Name", headerStyle);
            styledCell(header, 2, "Date", headerStyle);
            styledCell(header, 3, "Day", headerStyle);

            // Data rows
            for (int i = 0; i < holidays.size(); i++) {
                HolidayItem item = holidays.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(i + 1);
                row.createCell(1).setCellValue(item.name());
                row.createCell(2).setCellValue(item.date().format(DATE_FMT));
                row.createCell(3).setCellValue(item.date().format(DAY_FMT));
            }

            sheet.setColumnWidth(0, 2000);
            sheet.setColumnWidth(1, 10000);
            sheet.setColumnWidth(2, 4500);
            sheet.setColumnWidth(3, 4000);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate holidays Excel: " + e.getMessage(), e);
        }
    }

    private CellStyle headerStyle(Workbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());

        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private void styledCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }
}
