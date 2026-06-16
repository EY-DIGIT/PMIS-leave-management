# Leave Management — Public Holiday Calendar API

Spring Boot 3.5 (Java 21) REST service backed by PostgreSQL 16. It lets you:

1. **Upload public holidays** for a year — the dates are marked onto that year's
   calendar (1-Jan to 31-Dec).
2. **Read calendar data** for a year — the number of Saturdays and Sundays plus
   the public holidays with their dates.

## Prerequisites

- Java 21 (already installed)
- PostgreSQL 16 — easiest via the bundled Docker Compose file
- No Maven install needed; use the bundled wrapper (`mvnw` / `mvnw.cmd`)

## Run it

Start Postgres 16:

```bash
docker compose up -d
```

Start the app (Windows PowerShell):

```powershell
.\mvnw.cmd spring-boot:run
```

or on bash:

```bash
./mvnw spring-boot:run
```

The app listens on `http://localhost:8080`. The `public_holiday` table is created
automatically on first start.

DB connection is configurable via env vars: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.

## API docs (Swagger)

Interactive API documentation is generated from the controllers via
[springdoc-openapi](https://springdoc.org/). With the app running:

- **Swagger UI** — http://localhost:8080/swagger-ui.html
- **OpenAPI JSON** — http://localhost:8080/v3/api-docs

You can try every endpoint directly from the Swagger UI page.

## API

### 1. Upload public holidays for a year

`POST /api/holidays`

```json
{
  "year": 2026,
  "holidays": [
    { "date": "2026-01-26", "name": "Republic Day" },
    { "date": "2026-08-15", "name": "Independence Day" },
    { "date": "2026-10-02", "name": "Gandhi Jayanti" }
  ]
}
```

- Every `date` must fall inside `year`, else `400 Bad Request`.
- A single date may carry **more than one** public holiday — just list each as its
  own `{ "date", "name" }` entry (e.g. a national day and a local festival on the
  same date). Uniqueness is on the `(date, name)` pair, so re-uploading the same
  date+name is a no-op while a new name on that date adds another holiday.
- Returns `201 Created` with the full, sorted holiday list for the year.

```json
{
  "year": 2026,
  "holidays": [
    { "date": "2026-01-26", "name": "Republic Day" },
    { "date": "2026-01-26", "name": "Town Founders Day" },
    { "date": "2026-08-15", "name": "Independence Day" }
  ]
}
```

```bash
curl -X POST http://localhost:8080/api/holidays \
  -H "Content-Type: application/json" \
  -d '{"year":2026,"holidays":[{"date":"2026-01-26","name":"Republic Day"},{"date":"2026-08-15","name":"Independence Day"}]}'
```

**Or upload an Excel file** (`multipart/form-data`). The same endpoint also accepts an
`.xlsx`/`.xls` file whose first sheet has a header row followed by `[date, name]` rows
(column A = date as an Excel date or `yyyy-MM-dd` text, column B = name). Pass the
target `year` as a form field:

```bash
curl -X POST http://localhost:8080/api/holidays \
  -F "year=2026" \
  -F "file=@holidays.xlsx"
```

| date         | name             |
|--------------|------------------|
| 2026-01-26   | Republic Day     |
| 2026-08-15   | Independence Day |

### 2. Get calendar data for a year

`GET /api/calendar/{year}?month={1-12|all}`

`month` is optional and defaults to `all`. Pass `1`–`12` for a single month, or
`all` for the whole year with a per-month breakdown.

```bash
curl http://localhost:8080/api/calendar/2026            # whole year (month=all)
curl "http://localhost:8080/api/calendar/2026?month=6"  # just June
```

Single month (`?month=6`) — `months` is empty and the top-level counts are for that month:

```json
{
  "year": 2026,
  "month": 6,
  "totalDays": 30,
  "saturdays": 4,
  "sundays": 4,
  "totalWeekendDays": 8,
  "publicHolidayCount": 1,
  "publicHolidays": [ { "date": "2026-06-12", "name": "June Holiday" } ],
  "months": []
}
```

All months (`?month=all`) — top-level counts are the year totals and `months` holds the
12 per-month summaries:

```json
{
  "year": 2026,
  "month": null,
  "totalDays": 365,
  "saturdays": 52,
  "sundays": 52,
  "totalWeekendDays": 104,
  "publicHolidayCount": 2,
  "publicHolidays": [ /* all holidays in the year */ ],
  "months": [
    {
      "year": 2026, "month": 1, "monthName": "January", "totalDaysInMonth": 31,
      "saturdays": 5, "sundays": 4, "totalWeekendDays": 9,
      "publicHolidayCount": 1, "publicHolidays": [ { "date": "2026-01-26", "name": "Republic Day" } ]
    }
    /* … months 2-12 … */
  ]
}
```

### 3. List / delete holidays

```bash
curl http://localhost:8080/api/holidays/2026            # all holidays for the year (month=all)
curl "http://localhost:8080/api/holidays/2026?month=6"  # only June's holidays
curl -X DELETE http://localhost:8080/api/holidays/2026-01-26   # remove ALL holidays on that date (404 if none)
curl -X DELETE "http://localhost:8080/api/holidays/2026-01-26?name=Town%20Founders%20Day"  # remove just one
```

## Leave requests

Employees apply for leave; the service computes the **chargeable working days**
by excluding Saturdays, Sundays and public holidays. A holiday that lands on a
weekend is counted once (as a weekend day), so the buckets always sum to the
calendar days: `totalCalendarDays = workingDays + weekendDays + holidayDays`.

### Apply for leave

`POST /api/leaves`

```bash
curl -X POST http://localhost:8080/api/leaves \
  -H "Content-Type: application/json" \
  -d '{"employeeName":"Asha","startDate":"2026-01-22","endDate":"2026-01-28","reason":"Vacation"}'
```

Response (given Republic Day on 2026-01-26):

```json
{
  "id": 1,
  "employeeName": "Asha",
  "startDate": "2026-01-22",
  "endDate": "2026-01-28",
  "reason": "Vacation",
  "status": "PENDING",
  "totalCalendarDays": 7,
  "workingDays": 4,
  "weekendDays": 2,
  "holidayDays": 1,
  "holidaysInRange": [ { "date": "2026-01-26", "name": "Republic Day" } ],
  "appliedOn": "2026-06-10T08:30:00Z"
}
```

### Import leaves from an attendance sheet

`POST /api/leaves/from-attendance` (`multipart/form-data`)

Upload a monthly attendance export and the service marks absences as leave. In the
sheet, each employee spans **3 rows** (In-Time / Out-Time / Total-Time) with columns
`Attendance ID`, `Employee Name`, `Designation`, a label column, then day-of-month
columns `1…31`. A day where **both In-Time and Out-Time are `0`** is an absence.

- Absent **working days** are grouped into consecutive leave ranges and saved as
  `PENDING` leaves (a run is bridged across weekends/holidays but ends at the last
  absent working day).
- Absences on Saturdays, Sundays and stored public holidays are **skipped**.
- The employee name is fetched from the external directory by `Attendance ID`
  (see [External employee directory](#external-employee-directory) below).

```bash
curl -X POST http://localhost:8080/api/leaves/from-attendance \
  -F "month=6" \
  -F "year=2026" \
  -F "file=@attendance-june-2026.xlsx"
```

Returns `201` with the list of created leave records (each in the same shape as
"Apply for leave" above).

### Monthly attendance summary

`POST /api/attendance/summary` (`multipart/form-data`)

Upload the same monthly attendance sheet to get a read-only summary (nothing is
saved): the count of Saturdays, Sundays and public holidays in the month, and for
each employee the **leaves taken** (In=0 and Out=0 on a working day) and the number
of **worked days under 8 hours** (computed from In-Time and Out-Time).

```bash
curl -X POST http://localhost:8080/api/attendance/summary \
  -F "month=6" \
  -F "year=2026" \
  -F "file=@attendance-june-2026.xlsx"
```

Response:

```json
{
  "year": 2026,
  "month": 6,
  "totalDaysInMonth": 30,
  "saturdays": 4,
  "sundays": 4,
  "totalWeekendDays": 8,
  "publicHolidayCount": 1,
  "publicHolidays": [ { "date": "2026-06-12", "name": "Test Holiday" } ],
  "employeeCount": 1,
  "employees": [
    {
      "attendanceId": "E1",
      "employeeName": "Asha Kumar",
      "designation": "Dev",
      "leaveDays": 1,
      "shortHourDays": 2,
      "shortHourDayNumbers": [ 2, 4 ]
    }
  ]
}
```

The same summary is also available **without re-uploading** once a month has been
stored — see [Stored attendance & quarterly leave policy](#stored-attendance--quarterly-leave-policy).

### Other leave endpoints

```bash
curl http://localhost:8080/api/leaves                       # list all
curl "http://localhost:8080/api/leaves?employee=Asha"       # filter by employee
curl http://localhost:8080/api/leaves/1                     # get one
curl -X PATCH "http://localhost:8080/api/leaves/1/status?value=APPROVED"   # PENDING|APPROVED|REJECTED|CANCELLED
```

## Stored attendance & quarterly leave policy

To compute the **quarterly leave policy** (and to read the monthly summary without
re-uploading), first **store** each month's sheet, then query it.

### 1. Store a month

`POST /api/attendance/monthly` (`multipart/form-data`) — parses and saves the month's
absent weekdays and worked minutes. Re-uploading a month overwrites it.

```bash
curl -X POST http://localhost:8080/api/attendance/monthly -F "year=2024" -F "month=4" -F "file=@apr.xlsx"
curl -X POST http://localhost:8080/api/attendance/monthly -F "year=2024" -F "month=5" -F "file=@may.xlsx"
curl -X POST http://localhost:8080/api/attendance/monthly -F "year=2024" -F "month=6" -F "file=@jun.xlsx"
```

### 2. Monthly summary from stored data (GET)

`GET /api/attendance/summary?year={y}&month={1-12|all}` — read from stored data,
no upload. `month` defaults to `all`. The response shape depends on the scope:

- **`month=1..12`** → a single flat `MonthlyAttendanceSummary` (same shape as the
  upload response): weekend/holiday counts + per-employee leaves and short-hour days.
- **`month=all`** (default) → an envelope `{ year, month: null, months: [ … ] }` with
  one `MonthlyAttendanceSummary` per stored month, sorted.

```bash
curl "http://localhost:8080/api/attendance/summary?year=2024&month=6"     # one month (flat)
curl "http://localhost:8080/api/attendance/summary?year=2024&month=all"   # every stored month
curl "http://localhost:8080/api/attendance/summary?year=2024"             # same as month=all
```

Note: the upload (`POST /api/attendance/summary`) now also **persists** the month,
so it's available here without a separate store call.

### 3. Quarterly leave-policy settlement (GET)

`GET /api/attendance/quarterly-leave?year={y}&quarter={1-4}` — applies **UIDAI policy
5.24.1** across the quarter's stored attendance. Calendar quarters: Q1 Jan–Mar,
Q2 Apr–Jun, Q3 Jul–Sep, Q4 Oct–Dec.

- **6 paid leave days per quarter**; the rest are unpaid. Unused days lapse (no carry-forward).
- Mid-quarter joiners are **pro-rated by calendar days** (joining date comes from the
  external directory by `Attendance ID`).
- **Sandwich rule (5.24.1.b):** a weekend/holiday is charged as unpaid when the absences
  on both sides are unpaid leave (or it trails an open unpaid stretch at quarter end);
  it is not charged when either side is a paid leave or a worked day.

```bash
curl "http://localhost:8080/api/attendance/quarterly-leave?year=2024&quarter=2"
```

Response (per resource):

```json
{
  "year": 2024, "quarter": 2,
  "quarterStart": "2024-04-01", "quarterEnd": "2024-06-30",
  "monthsWithData": [4, 5, 6],
  "resourceCount": 1,
  "resources": [
    {
      "attendanceId": "E1",
      "employeeName": "Resource A",
      "joiningDate": "2024-04-01",
      "calculation": {
        "permissibleLeave": 6,
        "leaveDaysTaken": 19,
        "paidLeaveDays": 6,
        "unpaidLeaveDays": 13,
        "sandwichDays": 6,
        "totalUnpaidDays": 19,
        "lapsedLeaveDays": 0,
        "paidLeaveDates": [ "..." ],
        "unpaidLeaveDates": [ "..." ],
        "sandwichDates": [ "..." ]
      }
    }
  ]
}
```

This matches RFP Illustration 2 (`totalUnpaidDays = 19`). Illustration 1 yields
`totalUnpaidDays = 1`. Both are covered by `QuarterLeavePolicyTest`.

## Employee directory

The attendance import resolves employee names by `Attendance ID` through the
[EmployeeDirectoryClient](src/main/java/com/example/leavemanagement/client/EmployeeDirectoryClient.java)
seam. Currently a local [stub](src/main/java/com/example/leavemanagement/client/StubEmployeeDirectoryClient.java)
supplies the data — **no external API is required**. Joining date is unknown, so the
quarterly policy treats every resource as present from the quarter start (no pro-rata).

To source names/joining dates from a real system later, add another
`EmployeeDirectoryClient` implementation annotated `@Primary`; nothing else changes.

## Test

```bash
./mvnw test
```

Tests run against in-memory H2, so no live Postgres is required to build.

## Project layout

```
controller/HolidayController.java       holiday upload (JSON or Excel) / calendar / list / delete
controller/LeaveController.java         apply for leave + import from attendance + list / get / status
controller/AttendanceController.java    monthly summary (upload + stored) + quarterly leave settlement
service/HolidayService.java             upsert holidays + weekend counting
service/HolidayExcelParser.java         read holiday rows from an .xlsx/.xls upload
service/LeaveService.java               working-day calc (excludes weekends + holidays)
service/AttendanceExcelParser.java      read attendance sheet -> per-employee absences + worked minutes
service/AttendanceLeaveService.java     group absences into leaves + monthly summary
service/AttendanceQueryService.java     store monthly attendance + stored summary + quarter settlement
service/QuarterLeavePolicy.java         pure UIDAI 5.24.1 engine (paid/unpaid + sandwich + pro-rata)
client/EmployeeDirectoryClient.java     external directory lookup by attendance id (stubbed)
config/OpenApiConfig.java               Swagger / OpenAPI metadata
repository/                             Spring Data JPA repositories
entity/                                 public_holiday + leave_request tables
dto/                                    request/response records
exception/                              400 / 404 handling
```
