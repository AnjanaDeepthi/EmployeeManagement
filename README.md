# Labor Service: Employee / Role API + Labor Management API

A Spring Boot implementation of two designs from the API Design Casebook:

- **Employee / Role API**: employees, roles, permissions, location-scoped role assignments, privilege-escalation checks, authorization checks, POS PIN sign-in, termination and an audit trail.
- **Labor Management API** (shifts and payroll): schedules and shifts, shift swaps, time off, clock-in/out punches, computed timecards, manager adjustments, approvals, tips, effective-dated pay rates, pay periods, overtime rules (US federal and California) and an exactly-once payroll export.

It runs with nothing to install except a JDK: the database is in-memory H2 and demo data is loaded on startup.

## Requirements

- JDK 17 or newer
- Maven 3.9+ (IntelliJ IDEA ships with one, so you don't need to install it)

## Import into IntelliJ IDEA

1. **File → Open…** and pick this folder (or its `pom.xml`), then choose **Open as Project**.
2. Wait for Maven to download dependencies (bottom-right progress bar).
3. Make sure **File → Project Structure → Project SDK** is 17 or newer.
4. Run `LaborServiceApplication` (green arrow next to `main`).

Or from a terminal: `mvn spring-boot:run`

Then open:

| What | URL |
|---|---|
| Swagger UI (try every endpoint) | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| H2 database console | http://localhost:8080/h2-console (JDBC URL `jdbc:h2:mem:labor`, user `sa`, no password) |

`requests.http` contains a ready-made walkthrough you can run from IntelliJ's HTTP Client (Ultimate edition). In Community edition, use Swagger UI or the curl commands below.

## Run the tests

```
mvn test
```

- `EmployeeRoleApiTest`: escalation checks, scope checks, idempotent grants, cross-tenant 404s, termination, If-Match, PIN lockout.
- `LaborApiTest`: punch state machine and idempotency, manager overrides, early clock-in, forgotten clock-out → adjustment → approval, lock → summary → export exactly once, California overtime, pay rates, schedules, swaps and time off.
- `CalculatorTest`: plain unit tests for the timecard and overtime calculators.

## How "who is calling" works (kept simple on purpose)

Every request to `/v1/**` must send:

| Header | Meaning |
|---|---|
| `X-Tenant-Id` | The restaurant group, e.g. `ten_pecan` |
| `X-Employee-Id` | The caller, e.g. `emp_gm_tanya` |
| `X-Pin-Session` | Alternative to `X-Employee-Id`: the token from `POST /v1/pos/pin_sessions` (POS sign-in) |

In production these would come from a verified OAuth token, never from headers the client controls. Everything after that point (permission checks, tenant isolation, separation of duties) is real.

## Demo data

Tenant `ten_pecan` with two locations:

| Location | Time zone | Overtime rules |
|---|---|---|
| `loc_deepellum` | America/Chicago | `US_FEDERAL` (over 40 h/week) |
| `loc_la` | America/Los_Angeles | `US_CALIFORNIA` (over 8 h/day, double time over 12 h, over 40 h/week) |

| Employee | Role and scope | PIN | Use it to… |
|---|---|---|---|
| `emp_owner_olivia` | Owner, whole tenant | | do anything, create custom roles |
| `emp_gm_tanya` | General Manager, Deep Ellum | | schedule, approve timecards, grant roles at Deep Ellum |
| `emp_lead_jordan` | Shift Lead, Deep Ellum | 2468 | adjust timecards, punch for others (can't approve or see pay) |
| `emp_maria` | Staff (line cook), Deep Ellum | 1234 | clock in: her shift started 20 minutes ago |
| `emp_marcus` | Staff (server), Deep Ellum | 5678 | **forgot to clock out** 16 hours ago |
| `emp_sam` | Staff (host), Deep Ellum, **under 18** | 1111 | early clock-in is refused (shift starts in 2 h) |
| `emp_payroll_priya` | Payroll Admin, whole tenant | | pay rates, summaries, lock, export |
| `emp_gm_luis` | General Manager, Los Angeles | | LA approvals |
| `emp_ana` | Staff (line cook), Los Angeles | 4321 | California daily overtime last week |
| `emp_other_owner` | Owner of **another tenant** (`ten_other`) | | proves cross-tenant requests return 404 |

Pay periods: `pp_dallas_last_week` (Maria worked 45 h → 5 h overtime; one of Marcus's days is not yet approved, so locking fails until it is), `pp_dallas_this_week`, `pp_la_last_week`.

## A 5-minute walkthrough (curl)

```bash
H='-H Content-Type:application/json -H X-Tenant-Id:ten_pecan'

# 1. Maria clocks in (try sending it twice: same client_punch_id = same punch back)
curl -s -X POST localhost:8080/v1/punches $H -H X-Employee-Id:emp_maria \
  -d '{"client_punch_id":"dev1:0001","location_id":"loc_deepellum","type":"CLOCK_IN","job_code":"line_cook"}'

# 2. Who's on the clock? (Maria, and Marcus who never clocked out)
curl -s localhost:8080/v1/locations/loc_deepellum/on_the_clock $H -H X-Employee-Id:emp_lead_jordan

# 3. A GM tries to grant a role with payroll permissions she doesn't have -> 403 escalation_denied
curl -s -X POST localhost:8080/v1/employees/emp_maria/role_assignments $H -H X-Employee-Id:emp_gm_tanya \
  -d '{"role_id":"role_payroll_admin","scope":{"type":"location","id":"loc_deepellum"}}'

# 4. Lock last week's pay period -> 422 unapproved_timecards (lists what to approve)
curl -s -X POST localhost:8080/v1/pay_periods/pp_dallas_last_week/lock $H -H X-Employee-Id:emp_payroll_priya

# 5. Approve the listed timecard(s) as the GM, then lock again, then:
curl -s localhost:8080/v1/pay_periods/pp_dallas_last_week/summary $H -H X-Employee-Id:emp_payroll_priya
curl -s -X POST localhost:8080/v1/pay_periods/pp_dallas_last_week/exports $H -H X-Employee-Id:emp_payroll_priya \
  -H Idempotency-Key:export-1 -d '{"provider":"adp"}'
```

To fix Marcus's forgotten clock-out: `GET /v1/punches?employee_id=emp_marcus&location_id=loc_deepellum&from=…&to=…` gives his `timecard_id`. `GET /v1/timecards/{id}` returns an `ETag`. Then, as `emp_lead_jordan`:

```
POST /v1/timecards/{id}/adjustments
If-Match: "<etag>"
{"action":"ADD_PUNCH","punch_type":"CLOCK_OUT","occurred_at":"<clock-in + 7h>","reason_code":"forgot_clock_out"}
```

Then approve it as `emp_gm_tanya` with `POST /v1/timecards/{id}/approval`.

## Endpoint map

### Employee / Role API

| Method and path | What it does |
|---|---|
| `POST /v1/employees` | Invite (idempotent by email) |
| `GET /v1/employees?location_id=&role_id=&status=&cursor=&limit=` | List with cursor paging |
| `GET /v1/employees/{id}` / `PATCH` (If-Match) | Read / update (`me` works as an id) |
| `POST /v1/employees/{id}/invitation/acceptance` | Invitee activates their account |
| `POST /v1/employees/{id}/termination` | Revokes roles and POS sessions, keeps the record |
| `PUT /v1/employees/{id}/pin` | Set your own POS PIN |
| `GET /v1/permissions` | Permission catalog |
| `GET /v1/roles`, `POST /v1/roles`, `PATCH /v1/roles/{id}` (If-Match) | System and custom roles |
| `GET/POST /v1/employees/{id}/role_assignments`, `DELETE …/{assignmentId}` | Grant and revoke, scoped to tenant or location |
| `GET /v1/employees/{id}/effective_permissions?location_id=` | Resolved permissions and refund limit |
| `POST /v1/authorization/checks` | Batch "can X do Y at Z?" |
| `POST /v1/pos/pin_sessions` | POS sign-in with PIN (lockout after 5 failures) |
| `GET /v1/audit/role_changes` | Audit trail |

### Labor Management API

| Method and path | What it does |
|---|---|
| `POST /v1/locations/{lid}/schedules`, `GET /v1/schedules/{id}` | Weekly schedule (draft → published) |
| `POST /v1/schedules/{id}/shifts`, `PATCH`/`DELETE …/shifts/{shiftId}` | Shifts (overlap, 16 h max, assignment checks) |
| `POST /v1/schedules/{id}/publication` | Publish |
| `GET /v1/employees/{id}/shifts?from=&to=` | My shifts |
| `POST /v1/shifts/{id}/swap_requests`, `POST /v1/swap_requests/{id}/pickup` / `approval` / `denial` | Shift swaps |
| `GET /v1/locations/{lid}/swap_requests` | Open swap offers |
| `POST/GET /v1/employees/{id}/time_off_requests`, `POST /v1/time_off_requests/{id}/approval` / `denial` | Time off (approval lists shifts to re-cover) |
| `POST /v1/punches` | Clock in / out, breaks (idempotent by `client_punch_id`) |
| `GET /v1/punches?employee_id=&location_id=&from=&to=` | Punch log (cursor paging) |
| `GET /v1/locations/{lid}/on_the_clock` | Who is working now |
| `GET /v1/timecards?pay_period_id=` or `?location_id=&from=&to=` | Timecards for review |
| `GET /v1/timecards/{id}` | Segments, totals, violations, adjustment history |
| `POST /v1/timecards/{id}/adjustments` (If-Match) | ADD / MOVE / VOID a punch, with a reason |
| `POST /v1/timecards/{id}/approval` | Approve (not your own) |
| `POST /v1/employees/{id}/tip_declarations` | Declare cash tips |
| `GET/POST /v1/employees/{id}/pay_rates` | Effective-dated rates (restricted) |
| `POST /v1/locations/{lid}/pay_periods`, `GET /v1/pay_periods`, `GET /v1/pay_periods/{id}` | Pay periods |
| `POST /v1/pay_periods/{id}/lock` | Lock (all timecards approved) |
| `GET /v1/pay_periods/{id}/summary` | Regular / OT / double time, gross wages, tips |
| `POST/GET /v1/pay_periods/{id}/prior_period_adjustments` | Fixes for locked dates, paid in the open period |
| `POST/GET /v1/pay_periods/{id}/exports` | Export exactly once (Idempotency-Key, `supersedes`) |

## Where the interesting decisions live in the code

| Design point | Where to look |
|---|---|
| No privilege escalation; only owners grant owner | `RoleService.grant` → `checkNoEscalation` |
| Location-scoped permissions | `AuthorizationService.effective` / `RoleAssignment.covers` |
| Cross-tenant IDs return 404, never 403 | every `Repository.require(id, tenantId)` |
| PIN brute-force lockout, hashed PINs | `PinService` |
| Termination keeps the record, revokes access | `EmployeeService.terminate` |
| Punch idempotency, state machine, per-employee lock | `PunchService.record`, `ClockStateRepository.lockForUpdate` |
| Early clock-in, minor curfew, clock drift, offline sync | `PunchService.record` |
| Punches are immutable; timecards are computed | `Punch` (no setters), `TimecardCalculator`, `TimecardService.applyAdjustments` |
| Forgotten clock-outs are flagged, never auto-closed | `OpenShiftReviewJob` |
| Separation of duties (no self-approval / self-adjustment) | `TimecardService.adjust` / `approve` |
| Business date with a 4 AM cutoff | `BusinessDates` |
| Federal vs California overtime | `OvertimeCalculator` |
| Locked periods are frozen; prior-period adjustments | `PayPeriodGuard`, `PayrollService.addPriorPeriodAdjustment` |
| Exactly-once payroll export | `PayrollService.export` |
| Optimistic concurrency (ETag / If-Match, 428 / 412) | `ETags` |
| Errors as `application/problem+json` with a stable `code` | `ApiException`, `GlobalExceptionHandler` |
| Cursor (keyset) paging | `CursorPage`, `EmployeeRepository.page`, `PunchRepository.page` |

## What is simplified compared with the design

- **Authentication** uses headers (see above) instead of OAuth tokens.
- **Rate limiting** is not implemented (it belongs at the API gateway). PIN lockout is.
- **Events** (`punch.recorded`, `pay_period.locked`, …) are written to the audit log instead of a transactional outbox + Kafka.
- **Payroll export** runs synchronously and fakes the provider call. In production it would be an async job (`202` + status).
- **Permission caching** is not implemented; `AuthorizationService` reads the database on every check.
- **Tip pooling**, the California seventh-day rule and predictive-scheduling penalty pay are left out.
- **Database**: in-memory H2, recreated on every start. Switching to PostgreSQL is a datasource change in `application.yml` plus the driver dependency.
