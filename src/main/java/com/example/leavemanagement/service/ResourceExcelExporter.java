package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.ResourceResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Generates an Excel workbook from a list of {@link ResourceResponse}s, using the same
 * two-header-row format that {@code ResourceParser} can re-import.
 *
 * <p>Columns: Attendance ID | Employee Name | Role as per Contract | Location |
 * Date of Joining | Last Day of Working | Year-1..Year-7 | Category | Category Details
 */
@Component
public class ResourceExcelExporter {

    private static final List<String> RATE_YEARS =
            List.of("Year-1", "Year-2", "Year-3", "Year-4", "Year-5", "Year-6", "Year-7");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    public byte[] export(String projectId, List<ResourceResponse> resources) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Resources");

            CellStyle groupStyle = groupStyle(wb);
            CellStyle headerStyle = headerStyle(wb);

            // Row 0 — group heading (spanning all columns visually)
            Row group = sheet.createRow(0);
            styledCell(group, 0, "Resource Master — Project: " + (projectId != null ? projectId : "All"), groupStyle);

            // Row 1 — column labels (matches ResourceParser expected positions)
            Row labels = sheet.createRow(1);
            styledCell(labels, 0, "Attendance ID", headerStyle);
            styledCell(labels, 1, "Employee Name", headerStyle);
            styledCell(labels, 2, "Role as per Contract", headerStyle);
            styledCell(labels, 3, "Location", headerStyle);
            styledCell(labels, 4, "Date of Joining", headerStyle);
            styledCell(labels, 5, "Last Day of Working", headerStyle);
            for (int i = 0; i < RATE_YEARS.size(); i++) {
                styledCell(labels, 6 + i, RATE_YEARS.get(i), headerStyle);
            }
            styledCell(labels, 13, "Category", headerStyle);
            styledCell(labels, 14, "Category Details", headerStyle);

            // Data rows
            for (int i = 0; i < resources.size(); i++) {
                ResourceResponse r = resources.get(i);
                Row row = sheet.createRow(i + 2);
                row.createCell(0).setCellValue(orEmpty(r.resId()));
                row.createCell(1).setCellValue(orEmpty(r.name()));
                row.createCell(2).setCellValue(orEmpty(r.designationType()));
                row.createCell(3).setCellValue(orEmpty(r.location()));
                row.createCell(4).setCellValue(r.dateOfJoining() != null ? r.dateOfJoining().format(DATE_FMT) : "");
                row.createCell(5).setCellValue(r.lastDate() != null ? r.lastDate().format(DATE_FMT) : "");
                Map<String, Double> rateCard = r.rateCardByYear() != null ? r.rateCardByYear() : Map.of();
                for (int j = 0; j < RATE_YEARS.size(); j++) {
                    Double rate = rateCard.get(RATE_YEARS.get(j));
                    if (rate != null) {
                        row.createCell(6 + j).setCellValue(rate);
                    }
                }
                row.createCell(13).setCellValue(orEmpty(r.category()));
                row.createCell(14).setCellValue(orEmpty(r.categoryDetails()));
            }

            int[] widths = {4500, 8000, 8000, 5000, 5000, 5500, 3500, 3500, 3500, 3500, 3500, 3500, 3500, 3500, 5000};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i]);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate resources Excel: " + e.getMessage(), e);
        }
    }

    private CellStyle groupStyle(Workbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
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

    private String orEmpty(String s) {
        return s != null ? s : "";
    }
}
