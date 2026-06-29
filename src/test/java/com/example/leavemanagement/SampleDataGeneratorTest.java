package com.example.leavemanagement;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * Generates sample Excel files for manual testing.
 *
 * Run with:
 *   mvn test -Dtest=SampleDataGeneratorTest -DfailIfNoTests=false
 *
 * Output files are written to the project root directory:
 *   sample_holidays_2026.xlsx
 *   sample_attendance_june_2026.xlsx
 */
class SampleDataGeneratorTest {

    // Column layout expected by AttendanceExcelParser:
    // A(0)=AttendanceID  B(1)=Name  C(2)=Designation  D(3)=Label  E+(4..34)=Day1..Day31
    private static final int COL_ID    = 0;
    private static final int COL_NAME  = 1;
    private static final int COL_DESIG = 2;
    private static final int COL_LABEL = 3;

    // -------------------------------------------------------------------------
    // 1. Holiday calendar (for POST /api/holidays  multipart upload)
    // -------------------------------------------------------------------------

    @Test
    void generateHolidayCalendar2026() throws IOException {
        String path = outputPath("sample_holidays_2026.xlsx");

        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(path)) {

            Sheet sheet = wb.createSheet("Holidays 2026");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Date");        // Column A — yyyy-MM-dd text
            header.createCell(1).setCellValue("Holiday Name"); // Column B — name

            String[][] holidays = {
                {"2026-01-26", "Republic Day"},
                {"2026-03-25", "Holi"},
                {"2026-04-14", "Dr. B.R. Ambedkar Jayanti"},
                {"2026-04-17", "Good Friday"},
                {"2026-05-01", "Labour Day"},
                {"2026-06-05", "Eid al-Adha (Bakrid)"},
                {"2026-08-15", "Independence Day"},
                {"2026-08-19", "Janmashtami"},
                {"2026-10-02", "Gandhi Jayanti"},
                {"2026-10-20", "Dussehra (Vijaya Dashami)"},
                {"2026-11-12", "Diwali (Lakshmi Puja)"},
                {"2026-11-25", "Guru Nanak Jayanti"},
                {"2026-12-25", "Christmas Day"},
            };

            for (int i = 0; i < holidays.length; i++) {
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(holidays[i][0]);
                row.createCell(1).setCellValue(holidays[i][1]);
            }

            sheet.setColumnWidth(0, 4000);
            sheet.setColumnWidth(1, 9000);
            wb.write(fos);
        }

        System.out.println("Written: " + path);
    }

    // -------------------------------------------------------------------------
    // 2. Monthly attendance sheet (for POST /api/attendance/summary  multipart)
    // -------------------------------------------------------------------------
    //
    // June 2026 calendar (June 1 = Monday):
    //   Weekends (Sat/Sun): 6,7,13,14,20,21,27,28
    //   Working days: 22
    //
    // Employees:
    //   EMP001 Rajesh Kumar     — 3 absent days, 1 half-day, 1 short-hour day
    //   EMP002 Priya Sharma     — 3 consecutive absent days
    //   EMP003 Amit Singh       — 2 unpaid absent days (26-Fri, 29-Mon) that
    //                             sandwich the weekend 27-28, triggering the
    //                             UIDAI 5.24.1.b sandwich rule in quarterly report
    //                             (assumes >6 total leaves already used in Q2)

    @Test
    void generateAttendanceJune2026() throws IOException {
        String path = outputPath("sample_attendance_june_2026.xlsx");

        // June 2026 weekends
        boolean[] weekend = weekendFlags(6, 7, 13, 14, 20, 21, 27, 28);

        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(path)) {

            Sheet sheet = wb.createSheet("June 2026");

            // Header row (skipped by the parser — column D label won't match "intime")
            Row hdr = sheet.createRow(0);
            hdr.createCell(COL_ID).setCellValue("Attendance ID");
            hdr.createCell(COL_NAME).setCellValue("Employee Name");
            hdr.createCell(COL_DESIG).setCellValue("Designation");
            hdr.createCell(COL_LABEL).setCellValue("Type");
            for (int d = 1; d <= 30; d++) {
                hdr.createCell(COL_LABEL + d).setCellValue(d);
            }

            int nextRow = 1;

            // EMP001 – Rajesh Kumar, Software Engineer
            //   Absent:     day 2 (Tue), day 3 (Wed), day 10 (Wed)
            //   Half-day:   day 19 (Fri)  → 09:00–13:00 (4 h exactly)
            //   Short-hour: day 25 (Thu)  → 09:00–16:30 (7.5 h)
            Map<Integer, String[]> special1 = new HashMap<>();
            special1.put(19, new String[]{"09:00", "13:00"});
            special1.put(25, new String[]{"09:00", "16:30"});
            nextRow = writeEmployee(sheet, nextRow, "EMP001", "Rajesh Kumar",
                    "Software Engineer", weekend, absentFlags(2, 3, 10), 30, special1);

            // EMP002 – Priya Sharma, Business Analyst
            //   Absent: day 15 (Mon), day 16 (Tue), day 17 (Wed)
            nextRow = writeEmployee(sheet, nextRow, "EMP002", "Priya Sharma",
                    "Business Analyst", weekend, absentFlags(15, 16, 17), 30, new HashMap<>());

            // EMP003 – Amit Singh, Project Manager
            //   Absent: day 26 (Fri), day 29 (Mon) — both are unpaid (6 leaves already used)
            //   Sandwich: Sat 27 + Sun 28 become unpaid days in the quarterly report
            nextRow = writeEmployee(sheet, nextRow, "EMP003", "Amit Singh",
                    "Project Manager", weekend, absentFlags(26, 29), 30, new HashMap<>());

            // Column widths
            sheet.setColumnWidth(COL_ID,    4000);
            sheet.setColumnWidth(COL_NAME,  6000);
            sheet.setColumnWidth(COL_DESIG, 6000);
            sheet.setColumnWidth(COL_LABEL, 4500);

            wb.write(fos);
        }

        System.out.println("Written: " + path);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Writes 3 rows for one employee (In-Time / Out-Time / Total-Time).
     * Returns the next available row index.
     *
     * Default schedule: 09:00 in, 18:00 out, 09:00 total (9 hours).
     * Weekend / absent days → "0:00" on all three rows.
     * Special days → times from the provided map; total computed automatically.
     */
    private int writeEmployee(Sheet sheet, int startRow,
                              String id, String name, String desig,
                              boolean[] weekend, boolean[] absent,
                              int totalDays, Map<Integer, String[]> special) {
        Row inRow  = sheet.createRow(startRow);
        Row outRow = sheet.createRow(startRow + 1);
        Row totRow = sheet.createRow(startRow + 2);

        for (Row r : new Row[]{inRow, outRow, totRow}) {
            r.createCell(COL_ID).setCellValue(id);
            r.createCell(COL_NAME).setCellValue(name);
            r.createCell(COL_DESIG).setCellValue(desig);
        }
        inRow .createCell(COL_LABEL).setCellValue("In-Time");
        outRow.createCell(COL_LABEL).setCellValue("Out-Time");
        totRow.createCell(COL_LABEL).setCellValue("Total-Time");

        for (int d = 1; d <= totalDays; d++) {
            int col = COL_LABEL + d; // parser: col = LABEL_COL + day
            if (weekend[d] || absent[d]) {
                inRow .createCell(col).setCellValue("0:00");
                outRow.createCell(col).setCellValue("0:00");
                totRow.createCell(col).setCellValue("0:00");
            } else if (special.containsKey(d)) {
                String[] times = special.get(d);
                inRow .createCell(col).setCellValue(times[0]);
                outRow.createCell(col).setCellValue(times[1]);
                totRow.createCell(col).setCellValue(duration(times[0], times[1]));
            } else {
                inRow .createCell(col).setCellValue("09:00");
                outRow.createCell(col).setCellValue("18:00");
                totRow.createCell(col).setCellValue("09:00");
            }
        }
        return startRow + 3;
    }

    private static boolean[] weekendFlags(int... days) {
        boolean[] flags = new boolean[32]; // index 1..31
        for (int d : days) flags[d] = true;
        return flags;
    }

    private static boolean[] absentFlags(int... days) {
        boolean[] flags = new boolean[32];
        for (int d : days) flags[d] = true;
        return flags;
    }

    private static String duration(String in, String out) {
        int inMin  = toMinutes(in);
        int outMin = toMinutes(out);
        int dur    = outMin - inMin;
        if (dur < 0) dur += 24 * 60;
        return String.format("%d:%02d", dur / 60, dur % 60);
    }

    private static int toMinutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    private static String outputPath(String filename) {
        return Paths.get(System.getProperty("user.dir"), filename).toAbsolutePath().toString();
    }
}
