package com.example.leavemanagement.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/**
 * Generates blank upload-template Excel files whose column layout matches exactly what each
 * parser ({@code AttendanceExcelParser}, {@code ResourceParser}, {@code HolidayExcelParser})
 * expects on import.
 */
@Service
public class TemplateService {

    private static final int TEMPLATE_EMPLOYEE_SLOTS = 15;
    private static final String[] RATE_YEARS =
            {"Year-1", "Year-2", "Year-3", "Year-4", "Year-5", "Year-6", "Year-7"};

    // ------------------------------------------------------------------
    // Attendance template
    // ------------------------------------------------------------------

    /**
     * Blank attendance template for the given date range.
     *
     * <p>Layout matches {@code AttendanceExcelParser}:
     * <ul>
     *   <li>Col A — Attendance ID, B — Employee Name, C — Designation, D — Type
     *   <li>Cols E.. — one column per calendar day of the period (day-number header)
     *   <li>Each employee occupies 3 rows: In-Time / Out-Time / Total-Time (pre-labelled in col D)
     * </ul>
     */
    public byte[] attendanceTemplate(LocalDate startDate, LocalDate endDate) {
        int days = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Attendance");
            sheet.createFreezePane(4, 1); // freeze first 4 cols + header row

            CellStyle hdrStyle     = darkBlueHeader(wb);
            CellStyle dayHdrStyle  = dayHeader(wb);
            CellStyle inStyle      = typeLabel(wb, IndexedColors.LIGHT_GREEN);
            CellStyle outStyle     = typeLabel(wb, IndexedColors.LIGHT_ORANGE);
            CellStyle totalStyle   = typeLabel(wb, IndexedColors.LIGHT_CORNFLOWER_BLUE);
            CellStyle timeFmt      = timeCell(wb);

            // ---- Row 0: header ----
            Row hdr = sheet.createRow(0);
            hdr.setHeightInPoints(18);
            cell(hdr, 0, "Attendance ID", hdrStyle);
            cell(hdr, 1, "Employee Name",  hdrStyle);
            cell(hdr, 2, "Designation",    hdrStyle);
            cell(hdr, 3, "Type",           hdrStyle);
            for (int d = 1; d <= days; d++) {
                cell(hdr, 3 + d, String.valueOf(d), dayHdrStyle);
            }

            // ---- Employee rows (pre-labelled, data cells blank) ----
            int rowIdx = 1;
            for (int emp = 0; emp < TEMPLATE_EMPLOYEE_SLOTS; emp++) {
                Row inRow    = sheet.createRow(rowIdx++);
                Row outRow   = sheet.createRow(rowIdx++);
                Row totalRow = sheet.createRow(rowIdx++);

                cell(inRow,    3, "In-Time",    inStyle);
                cell(outRow,   3, "Out-Time",   outStyle);
                cell(totalRow, 3, "Total-Time", totalStyle);

                // Create time-formatted blank cells for each day
                for (int d = 1; d <= days; d++) {
                    inRow.createCell(3 + d).setCellStyle(timeFmt);
                    outRow.createCell(3 + d).setCellStyle(timeFmt);
                    totalRow.createCell(3 + d).setCellStyle(timeFmt);
                }
            }

            // ---- Column widths ----
            sheet.setColumnWidth(0, 4000); // Attendance ID
            sheet.setColumnWidth(1, 7500); // Employee Name
            sheet.setColumnWidth(2, 8000); // Designation
            sheet.setColumnWidth(3, 3800); // Type
            for (int d = 1; d <= days; d++) {
                sheet.setColumnWidth(3 + d, 2200);
            }

            return toBytes(wb);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate attendance template: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Resource master template
    // ------------------------------------------------------------------

    /**
     * Blank resource master template.
     *
     * <p>Layout matches {@code ResourceParser}:
     * <ul>
     *   <li>Row 1 — column headers (1 header row; parser skips HEADER_ROWS = 1)
     *   <li>Cols A–H: Attendance ID | Employee Name | Role as per Contract | Location |
     *       Date of Joining | Last Day of Working | Category (RFP/CCN/ASG) | CCN/ASG Details
     * </ul>
     * Rate card columns (Year-1..Year-7) are no longer in the resource Excel — rates are
     * resolved from the designation rate master by role at upload time.
     */
    public byte[] resourceTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Resources");

            CellStyle hdrStyle  = darkBlueHeader(wb);
            CellStyle noteStyle = noteCell(wb);

            // Row 0 — column labels (1 header row, parser skips HEADER_ROWS = 1)
            Row labels = sheet.createRow(0);
            labels.setHeightInPoints(18);
            cell(labels, 0, "Attendance ID",          hdrStyle);
            cell(labels, 1, "Employee Name",           hdrStyle);
            cell(labels, 2, "Role as per Contract",    hdrStyle);
            cell(labels, 3, "Location",                hdrStyle);
            cell(labels, 4, "Date of Joining",         hdrStyle);
            cell(labels, 5, "Last Day of Working",     hdrStyle);
            cell(labels, 6, "Category (RFP/CCN/ASG)", hdrStyle);
            cell(labels, 7, "CCN/ASG Details",         hdrStyle);

            // Row 1 — sample-format hint row
            Row hint = sheet.createRow(1);
            cell(hint, 0, "e.g. 421",                  noteStyle);
            cell(hint, 1, "Employee Full Name",         noteStyle);
            cell(hint, 2, "Role as per Contract",       noteStyle);
            cell(hint, 3, "e.g. Chennai",               noteStyle);
            cell(hint, 4, "yyyy-MM-dd",                 noteStyle);
            cell(hint, 5, "yyyy-MM-dd or leave blank",  noteStyle);
            cell(hint, 6, "RFP / CCN / ASG",            noteStyle);
            cell(hint, 7, "e.g. CCN001 or NA",          noteStyle);

            // Empty data rows
            for (int r = 2; r < 2 + TEMPLATE_EMPLOYEE_SLOTS; r++) {
                sheet.createRow(r);
            }

            int[] widths = {4500, 8000, 9000, 5000, 5500, 5500, 5500, 5000};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i]);
            }

            return toBytes(wb);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate resource template: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Designation rate card template
    // ------------------------------------------------------------------

    /**
     * Blank designation rate card template.
     *
     * <p>Layout matches {@code DesignationRateParser}:
     * <ul>
     *   <li>Row 0 — header: Role as per Contract | Year-1 Rate | … | Year-7 Rate
     *   <li>Row 1 — italic format-hint row
     *   <li>Rows 2+ — empty data rows (one per role)
     * </ul>
     */
    public byte[] designationRateTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Designation Rates");

            CellStyle hdrStyle  = darkBlueHeader(wb);
            CellStyle noteStyle = noteCell(wb);

            // Row 0 — column headers
            Row hdr = sheet.createRow(0);
            hdr.setHeightInPoints(18);
            cell(hdr, 0, "Role as per Contract", hdrStyle);
            for (int i = 0; i < RATE_YEARS.length; i++) {
                cell(hdr, 1 + i, RATE_YEARS[i] + " Rate", hdrStyle);
            }

            // Row 1 — format hint
            Row hint = sheet.createRow(1);
            cell(hint, 0, "e.g. Security Crypto Lead", noteStyle);
            for (int i = 0; i < RATE_YEARS.length; i++) {
                cell(hint, 1 + i, "monthly rate (number)", noteStyle);
            }

            // Empty data rows
            for (int r = 2; r < 2 + TEMPLATE_EMPLOYEE_SLOTS; r++) {
                sheet.createRow(r);
            }

            sheet.setColumnWidth(0, 12000);
            for (int i = 0; i < RATE_YEARS.length; i++) {
                sheet.setColumnWidth(1 + i, 4500);
            }

            return toBytes(wb);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate designation rate template: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Holiday template
    // ------------------------------------------------------------------

    /**
     * Blank holiday template for the given year.
     *
     * <p>Layout matches {@code HolidayExcelParser} / {@code HolidayExcelExporter}:
     * <ul>
     *   <li>Row 1 — header: S.No | Holiday Name | Date | Day
     *   <li>Date column accepts "dd MMMM" text (e.g. "26 January") resolved against {@code year},
     *       a full Excel date cell, or yyyy-MM-dd.
     * </ul>
     */
    public byte[] holidayTemplate(int year) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Holidays " + year);

            CellStyle hdrStyle  = darkBlueHeader(wb);
            CellStyle noteStyle = noteCell(wb);

            // Row 0 — header
            Row hdr = sheet.createRow(0);
            hdr.setHeightInPoints(18);
            cell(hdr, 0, "S.No",         hdrStyle);
            cell(hdr, 1, "Holiday",       hdrStyle);
            cell(hdr, 2, "Date",          hdrStyle);
            cell(hdr, 3, "Day",           hdrStyle);

            // Row 1 — format hint
            Row hint = sheet.createRow(1);
            cell(hint, 0, "1",                  noteStyle);
            cell(hint, 1, "Holiday Name",        noteStyle);
            cell(hint, 2, "e.g. 26 January",    noteStyle);
            cell(hint, 3, "e.g. Monday",         noteStyle);

            // Empty data rows (enough for a full year's holiday list)
            for (int r = 2; r <= 30; r++) {
                sheet.createRow(r);
            }

            sheet.setColumnWidth(0, 2000);
            sheet.setColumnWidth(1, 10000);
            sheet.setColumnWidth(2, 5000);
            sheet.setColumnWidth(3, 4500);

            return toBytes(wb);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate holiday template: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Shared style helpers
    // ------------------------------------------------------------------

    private CellStyle darkBlueHeader(XSSFWorkbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());

        CellStyle s = wb.createCellStyle();
        s.setFont(font);
        s.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setBorderBottom(BorderStyle.THIN);
        return s;
    }

    private CellStyle dayHeader(XSSFWorkbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());

        CellStyle s = wb.createCellStyle();
        s.setFont(font);
        s.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setBorderBottom(BorderStyle.THIN);
        return s;
    }

    private CellStyle typeLabel(XSSFWorkbook wb, IndexedColors bg) {
        Font font = wb.createFont();
        font.setBold(true);

        CellStyle s = wb.createCellStyle();
        s.setFont(font);
        s.setFillForegroundColor(bg.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setBorderRight(BorderStyle.THIN);
        return s;
    }

    private CellStyle timeCell(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        // HH:mm display format for time entry
        s.setDataFormat(wb.createDataFormat().getFormat("HH:MM"));
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setBorderLeft(BorderStyle.HAIR);
        s.setBorderRight(BorderStyle.HAIR);
        return s;
    }


    private CellStyle noteCell(XSSFWorkbook wb) {
        Font font = wb.createFont();
        font.setItalic(true);
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

        CellStyle s = wb.createCellStyle();
        s.setFont(font);
        s.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private byte[] toBytes(XSSFWorkbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }
}
