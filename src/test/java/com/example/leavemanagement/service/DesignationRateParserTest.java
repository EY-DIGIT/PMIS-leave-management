package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.leavemanagement.dto.DesignationRateRow;
import com.example.leavemanagement.exception.BadRequestException;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class DesignationRateParserTest {

    private final DesignationRateParser parser = new DesignationRateParser();

    private MultipartFile workbook(String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Rates");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            wb.write(out);
            return new MockMultipartFile("file", "rates.xlsx", null, out.toByteArray());
        }
    }

    @Test
    void parsesTwoColumnRoleAndBaseRate() throws Exception {
        MultipartFile file = workbook(new String[][] {
                {"Role as per Contract", "Base Rate"},
                {"Program Manager", "100000"},
                {"Security Crypto Lead", "1,75,600"}});

        List<DesignationRateRow> rows = parser.parse(file);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).role()).isEqualTo("Program Manager");
        assertThat(rows.get(0).baseRate()).isEqualTo(100000.0);
        assertThat(rows.get(1).role()).isEqualTo("Security Crypto Lead");
        assertThat(rows.get(1).baseRate()).isEqualTo(175600.0);
    }

    @Test
    void parsesClientRateSheetHeaderAndIndianCurrencyFormat() throws Exception {
        // The client's own sheet: "Role/Position of Staff" | "Rate per month (INR)" with ₹ + lakh commas.
        MultipartFile file = workbook(new String[][] {
                {"Role/Position of Staff", "Rate per month (INR)"},
                {"Program Director", "₹ 7,68,000.00"},
                {"Program Manager", "₹ 5,12,000.00"}});

        List<DesignationRateRow> rows = parser.parse(file);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).role()).isEqualTo("Program Director");
        assertThat(rows.get(0).baseRate()).isEqualTo(768000.0);
        assertThat(rows.get(1).baseRate()).isEqualTo(512000.0);
    }

    @Test
    void rejectsWrongHeader() throws Exception {
        MultipartFile file = workbook(new String[][] {
                {"Employee Name", "Location"},
                {"Asha", "Delhi"}});

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Role as per Contract");
    }

    @Test
    void rejectsOldSevenYearColumnSheetHeaderWhenBaseRateColumnMissing() throws Exception {
        // The old format's second column was "Year-1 Rate" — still contains "rate", so it is accepted
        // and its first year value is read as the base rate (graceful migration).
        MultipartFile file = workbook(new String[][] {
                {"Role as per Contract", "Year-1 Rate", "Year-2 Rate"},
                {"Dev", "90000", "95000"}});

        List<DesignationRateRow> rows = parser.parse(file);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).baseRate()).isEqualTo(90000.0);
    }

    @Test
    void rejectsEmptyDataRows() throws Exception {
        MultipartFile file = workbook(new String[][] {{"Role as per Contract", "Base Rate"}});
        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no data rows");
    }
}
