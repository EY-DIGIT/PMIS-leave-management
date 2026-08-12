package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.DesignationRateRow;
import com.example.leavemanagement.exception.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
 *   <li>Row 0 — header: "Role as per Contract" | "Base Rate"
 *   <li>Row 1+ — data rows, one per role
 * </ul>
 *
 * <p>Column A = Role name (string), column B = the project Year-1 monthly base rate (numeric). The later
 * project years' rates are generated in {@code DesignationRateService} from this base and the upload's
 * increase percentage.
 */
@Component
public class DesignationRateParser {

    private static final int HEADER_ROWS = 1;
    private static final int COL_ROLE = 0;
    private static final int COL_BASE_RATE = 1;

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
                double baseRate = numericValue(row.getCell(COL_BASE_RATE), evaluator);
                rows.add(new DesignationRateRow(role, baseRate));
            }
        } catch (IOException e) {
            throw new BadRequestException("Could not read the Excel file: " + e.getMessage());
        }

        if (rows.isEmpty()) throw new BadRequestException("The designation rate card file contains no data rows");
        return rows;
    }

    private void validateHeaderFormat(Sheet sheet, FormulaEvaluator evaluator) {
        Row header = sheet.getRow(0);
        String c0 = normalize(header == null ? null : text(header.getCell(COL_ROLE), evaluator));
        String c1 = normalize(header == null ? null : text(header.getCell(COL_BASE_RATE), evaluator));
        // Accept any role-name header ("Role as per Contract", "Role/Position of Staff", …) and any
        // rate header ("Base Rate", "Rate per month (INR)", …) so the client's own rate sheet uploads
        // without being reformatted to the template's exact wording.
        if (!c0.contains("role") || !c1.contains("rate")) {
            throw new BadRequestException(
                    "Invalid designation rate card file format. Expected column A to be the role "
                            + "(e.g. 'Role/Position of Staff' or 'Role as per Contract') and column B to be "
                            + "the monthly rate (e.g. 'Rate per month (INR)' or 'Base Rate'). Please upload the "
                            + "designation rate card — not the resource master or another file.");
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private boolean isBlank(Cell cell) {
        if (cell == null) return true;
        return formatter.formatCellValue(cell).trim().isEmpty();
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
        String raw = formatter.formatCellValue(cell, evaluator).trim().replaceAll("[,₹\\s]", "");
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
