package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.DesignationRateRow;
import com.example.leavemanagement.exception.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Parses the designation rate card Excel upload.
 *
 * <p>Expected layout (matches the template from {@code TemplateService#designationRateTemplate}):
 * <ul>
 *   <li>Row 0 — header: "Role as per Contract" | "Year-1 Rate" | ... | "Year-7 Rate"
 *   <li>Row 1+ — data rows, one per role
 * </ul>
 *
 * <p>Column A = Role name (string), columns B–H = Year-1 through Year-7 monthly rates (numeric).
 */
@Component
public class DesignationRateParser {

    private static final int HEADER_ROWS = 1;
    private static final int COL_ROLE = 0;
    private static final int COL_YEAR_1 = 1;
    private static final int YEAR_COLUMNS = 7;
    private static final String[] YEAR_KEYS =
            {"Year-1", "Year-2", "Year-3", "Year-4", "Year-5", "Year-6", "Year-7"};

    private final DataFormatter formatter = new DataFormatter();

    public List<DesignationRateRow> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("A designation rate card Excel file is required (field 'file')");
        }
        List<DesignationRateRow> rows = new ArrayList<>();
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) throw new BadRequestException("The workbook has no sheets");
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            validateHeaderFormat(sheet, evaluator);

            for (Row row : sheet) {
                if (row.getRowNum() < HEADER_ROWS) continue;
                Cell roleCell = row.getCell(COL_ROLE);
                if (isBlank(roleCell)) continue;

                String role = text(roleCell, evaluator).trim();
                Map<String, Double> rateCard = new LinkedHashMap<>();
                for (int i = 0; i < YEAR_COLUMNS; i++) {
                    Cell rateCell = row.getCell(COL_YEAR_1 + i);
                    double rate = numericValue(rateCell, evaluator);
                    if (rate > 0) {
                        rateCard.put(YEAR_KEYS[i], rate);
                    }
                }
                rows.add(new DesignationRateRow(role, rateCard));
            }
        } catch (IOException e) {
            throw new BadRequestException("Could not read the Excel file: " + e.getMessage());
        }

        if (rows.isEmpty()) throw new BadRequestException("The designation rate card file contains no data rows");
        return rows;
    }

    /**
     * Rejects a wrong file (e.g. the resource master) before parsing, by checking the header row's
     * discriminating columns: A = "Role as per Contract", B = a "Year-1" rate column.
     */
    private void validateHeaderFormat(Sheet sheet, FormulaEvaluator evaluator) {
        Row header = sheet.getRow(0);
        String colA = header == null ? "" : normalize(text(header.getCell(COL_ROLE), evaluator));
        String colB = header == null ? "" : normalize(text(header.getCell(COL_YEAR_1), evaluator));
        if (!colA.equals("roleaspercontract") || !colB.startsWith("year1")) {
            throw new BadRequestException(
                    "Invalid designation rate card file format. Expected the designation rate card template "
                            + "with a header row: 'Role as per Contract | Year-1 Rate | Year-2 Rate | … | "
                            + "Year-7 Rate'. Please upload the designation rate card file (not the resource "
                            + "master or another file).");
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private boolean isBlank(Cell cell) {
        if (cell == null) return true;
        String val = formatter.formatCellValue(cell).trim();
        return val.isEmpty();
    }

    private String text(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA
                ? evaluator.evaluateFormulaCell(cell)
                : cell.getCellType();
        if (type == CellType.NUMERIC) {
            return String.valueOf((long) cell.getNumericCellValue());
        }
        return formatter.formatCellValue(cell, evaluator).trim();
    }

    private double numericValue(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return 0;
        CellType type = cell.getCellType() == CellType.FORMULA
                ? evaluator.evaluateFormulaCell(cell)
                : cell.getCellType();
        if (type == CellType.NUMERIC) return cell.getNumericCellValue();
        String raw = formatter.formatCellValue(cell, evaluator).trim().replaceAll(",", "");
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
