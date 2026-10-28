package com.restaurant.labor.seed;

import com.restaurant.labor.employee.Employee;
import com.restaurant.labor.employee.EmployeeRepository;
import com.restaurant.labor.employee.EmployeeStatus;
import com.restaurant.labor.employee.Permission;
import com.restaurant.labor.employee.PinService;
import com.restaurant.labor.employee.Role;
import com.restaurant.labor.employee.RoleAssignment;
import com.restaurant.labor.employee.RoleAssignmentRepository;
import com.restaurant.labor.employee.RoleRepository;
import com.restaurant.labor.employee.ScopeType;
import com.restaurant.labor.labor.payroll.PayPeriod;
import com.restaurant.labor.labor.payroll.PayPeriodRepository;
import com.restaurant.labor.labor.payroll.PayRate;
import com.restaurant.labor.labor.payroll.PayRateRepository;
import com.restaurant.labor.labor.punch.ClockState;
import com.restaurant.labor.labor.punch.ClockStateRepository;
import com.restaurant.labor.labor.punch.Punch;
import com.restaurant.labor.labor.punch.PunchRepository;
import com.restaurant.labor.labor.punch.PunchSource;
import com.restaurant.labor.labor.punch.PunchType;
import com.restaurant.labor.labor.schedule.Schedule;
import com.restaurant.labor.labor.schedule.ScheduleRepository;
import com.restaurant.labor.labor.schedule.ScheduledShift;
import com.restaurant.labor.labor.schedule.ScheduledShiftRepository;
import com.restaurant.labor.labor.timecard.TimecardState;
import com.restaurant.labor.labor.timecard.TimecardStateRepository;
import com.restaurant.labor.location.BusinessDates;
import com.restaurant.labor.location.Location;
import com.restaurant.labor.location.LocationRepository;
import com.restaurant.labor.location.RuleSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Demo data so every endpoint can be tried immediately. All dates are relative to "today", so the demo
 * works whenever you run it. See README for the identities and a guided walkthrough.
 */
@Component
@ConditionalOnProperty(name = "labor.seed-demo-data", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    public static final String TENANT = "ten_pecan";
    public static final String OTHER_TENANT = "ten_other";
    public static final String DEEP_ELLUM = "loc_deepellum";
    public static final String LOS_ANGELES = "loc_la";

    private final LocationRepository locations;
    private final RoleRepository roles;
    private final EmployeeRepository employees;
    private final RoleAssignmentRepository assignments;
    private final PayRateRepository rates;
    private final ScheduleRepository schedules;
    private final ScheduledShiftRepository shifts;
    private final PunchRepository punches;
    private final ClockStateRepository clockStates;
    private final TimecardStateRepository timecards;
    private final PayPeriodRepository periods;

    private int punchSeq = 0;

    public DemoDataSeeder(LocationRepository locations, RoleRepository roles, EmployeeRepository employees,
                          RoleAssignmentRepository assignments, PayRateRepository rates, ScheduleRepository schedules,
                          ScheduledShiftRepository shifts, PunchRepository punches, ClockStateRepository clockStates,
                          TimecardStateRepository timecards, PayPeriodRepository periods) {
        this.locations = locations;
        this.roles = roles;
        this.employees = employees;
        this.assignments = assignments;
        this.rates = rates;
        this.schedules = schedules;
        this.shifts = shifts;
        this.punches = punches;
        this.clockStates = clockStates;
        this.timecards = timecards;
        this.periods = periods;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (locations.count() > 0) return;

        // ---------------- locations
        Location dallas = locations.save(new Location(DEEP_ELLUM, TENANT, "Pecan Lodge - Deep Ellum", "America/Chicago",
                LocalTime.of(4, 0), RuleSet.US_FEDERAL, 10, LocalTime.of(22, 0)));
        Location la = locations.save(new Location(LOS_ANGELES, TENANT, "Pecan Lodge - Los Angeles", "America/Los_Angeles",
                LocalTime.of(4, 0), RuleSet.US_CALIFORNIA, 10, LocalTime.of(22, 0)));
        locations.save(new Location("loc_other", OTHER_TENANT, "Another Restaurant Group", "America/New_York",
                LocalTime.of(4, 0), RuleSet.US_FEDERAL, 10, LocalTime.of(22, 0)));

        // ---------------- system roles (per tenant)
        Set<String> all = Arrays.stream(Permission.values()).map(Permission::code).collect(Collectors.toSet());
        Role owner = role("role_owner", TENANT, "owner", "Owner", all, null);
        Role gm = role("role_general_manager", TENANT, "general_manager", "General Manager",
                codes(EnumSet.complementOf(EnumSet.of(Permission.PAYROLL_EXPORT, Permission.PAY_RATES_MANAGE))), 20_000L);
        Role lead = role("role_shift_lead", TENANT, "shift_lead", "Shift Lead",
                codes(EnumSet.of(Permission.EMPLOYEES_READ, Permission.PUNCHES_WRITE_OTHERS, Permission.TIMECARDS_READ,
                        Permission.TIMECARDS_ADJUST, Permission.REQUESTS_APPROVE, Permission.REFUNDS_CREATE)), 5_000L);
        Role staff = role("role_staff", TENANT, "staff", "Staff (cook, server, host)", Set.of(), 0L);
        Role payroll = role("role_payroll_admin", TENANT, "payroll_admin", "Payroll Admin",
                codes(EnumSet.of(Permission.EMPLOYEES_READ, Permission.TIMECARDS_READ, Permission.PAY_PERIODS_MANAGE,
                        Permission.PAYROLL_READ, Permission.PAY_RATES_MANAGE, Permission.PAYROLL_EXPORT)), 0L);
        Role otherOwner = role("role_owner_other", OTHER_TENANT, "owner", "Owner", all, null);

        // ---------------- employees
        LocalDate hired = LocalDate.now().minusYears(1);
        emp("emp_owner_olivia", "Olivia Owner", false, null);
        emp("emp_gm_tanya", "Tanya (GM, Deep Ellum)", false, null);
        emp("emp_lead_jordan", "Jordan (Shift Lead, Deep Ellum)", false, "2468");
        emp("emp_maria", "Maria (Line Cook)", false, "1234");
        emp("emp_marcus", "Marcus (Server)", false, "5678");
        emp("emp_sam", "Sam (Host, under 18)", true, "1111");
        emp("emp_payroll_priya", "Priya (Payroll Admin)", false, null);
        emp("emp_gm_luis", "Luis (GM, Los Angeles)", false, null);
        emp("emp_ana", "Ana (Cook, Los Angeles)", false, "4321");
        employees.save(new Employee("emp_other_owner", OTHER_TENANT, "Other Tenant Owner", "owner@other.example",
                null, EmployeeStatus.ACTIVE, hired, false));

        grant("emp_owner_olivia", owner, ScopeType.TENANT, TENANT, TENANT);
        grant("emp_payroll_priya", payroll, ScopeType.TENANT, TENANT, TENANT);
        grant("emp_gm_tanya", gm, ScopeType.LOCATION, DEEP_ELLUM, TENANT);
        grant("emp_lead_jordan", lead, ScopeType.LOCATION, DEEP_ELLUM, TENANT);
        grant("emp_maria", staff, ScopeType.LOCATION, DEEP_ELLUM, TENANT);
        grant("emp_marcus", staff, ScopeType.LOCATION, DEEP_ELLUM, TENANT);
        grant("emp_sam", staff, ScopeType.LOCATION, DEEP_ELLUM, TENANT);
        grant("emp_gm_luis", gm, ScopeType.LOCATION, LOS_ANGELES, TENANT);
        grant("emp_ana", staff, ScopeType.LOCATION, LOS_ANGELES, TENANT);
        grant("emp_other_owner", otherOwner, ScopeType.TENANT, OTHER_TENANT, OTHER_TENANT);

        // ---------------- pay rates
        LocalDate longAgo = LocalDate.now().minusMonths(6);
        rate("emp_maria", "line_cook", 1800, longAgo);
        rate("emp_marcus", "server", 1200, longAgo);
        rate("emp_sam", "host", 1100, longAgo);
        rate("emp_lead_jordan", "shift_lead", 2400, longAgo);
        rate("emp_ana", "line_cook", 2000, longAgo);

        // ---------------- pay periods: last week (ready to approve/lock/export) and this week
        LocalDate today = BusinessDates.of(Instant.now(), dallas);
        LocalDate thisWeek = BusinessDates.weekStart(today);
        LocalDate lastWeek = thisWeek.minusDays(7);
        periods.save(new PayPeriod("pp_dallas_last_week", TENANT, DEEP_ELLUM, lastWeek, lastWeek.plusDays(6)));
        periods.save(new PayPeriod("pp_dallas_this_week", TENANT, DEEP_ELLUM, thisWeek, thisWeek.plusDays(6)));
        periods.save(new PayPeriod("pp_la_last_week", TENANT, LOS_ANGELES, lastWeek, lastWeek.plusDays(6)));

        // ---------------- last week's worked shifts (approved), to demo overtime and payroll
        // Maria: 5 x 9h worked (+30 min break) = 45h -> 5h federal overtime.
        for (int d = 0; d < 5; d++) {
            workedShift("emp_maria", dallas, lastWeek.plusDays(d), LocalTime.of(9, 0), 9 * 60, 30, "line_cook", true);
        }
        // Marcus: 3 x 6h. The last day is left unapproved so locking fails until a manager approves it.
        workedShift("emp_marcus", dallas, lastWeek.plusDays(1), LocalTime.of(16, 0), 6 * 60, 0, "server", true);
        workedShift("emp_marcus", dallas, lastWeek.plusDays(3), LocalTime.of(16, 0), 6 * 60, 0, "server", true);
        workedShift("emp_marcus", dallas, lastWeek.plusDays(5), LocalTime.of(17, 0), 6 * 60, 0, "server", false);
        // Ana (California): 2 x 11h with a 30 min break -> daily overtime after 8h.
        workedShift("emp_ana", la, lastWeek.plusDays(2), LocalTime.of(8, 0), 11 * 60, 30, "line_cook", true);
        workedShift("emp_ana", la, lastWeek.plusDays(3), LocalTime.of(8, 0), 11 * 60, 30, "line_cook", true);

        // ---------------- this week's published schedule; Maria's shift started 20 minutes ago
        Schedule schedule = schedules.save(new Schedule("sch_dallas_this_week", TENANT, DEEP_ELLUM, thisWeek, "emp_gm_tanya"));
        schedule.publish("emp_gm_tanya");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        shifts.save(new ScheduledShift("shf_maria_today", TENANT, schedule.getId(), DEEP_ELLUM, "emp_maria", "line_cook",
                now.minus(Duration.ofMinutes(20)), now.plus(Duration.ofHours(7).plusMinutes(40)), null));
        // Sam (a minor) is scheduled two hours from now; clocking in now is "too early" without a manager.
        shifts.save(new ScheduledShift("shf_sam_later", TENANT, schedule.getId(), DEEP_ELLUM, "emp_sam", "host",
                now.plus(Duration.ofHours(2)), now.plus(Duration.ofHours(6)), null));

        // ---------------- a forgotten clock-out: Marcus clocked in 16 hours ago and never clocked out
        Instant marcusIn = now.minus(Duration.ofHours(16));
        LocalDate marcusDay = BusinessDates.of(marcusIn, dallas);
        Punch open = punches.save(new Punch("pch_marcus_open", TENANT, "emp_marcus", DEEP_ELLUM, PunchType.CLOCK_IN,
                marcusIn, PunchSource.POS, "emp_marcus", "dev_pos_1", "server", "seed:marcus-open", null, marcusDay, ""));
        ClockState cs = new ClockState("emp_marcus", TENANT);
        cs.clockIn(open);
        clockStates.save(cs);
        if (timecards.findById(TimecardState.idFor("emp_marcus", marcusDay)).isEmpty()) {
            timecards.save(new TimecardState(TENANT, "emp_marcus", DEEP_ELLUM, marcusDay));
        }

        log.info("Demo data loaded: tenant {}, locations {} and {}. Swagger UI: http://localhost:8080/swagger-ui.html",
                TENANT, DEEP_ELLUM, LOS_ANGELES);
    }

    // ---------------------------------------------------------------------------------------------

    private static Set<String> codes(Set<Permission> perms) {
        return perms.stream().map(Permission::code).collect(Collectors.toSet());
    }

    private Role role(String id, String tenant, String key, String name, Set<String> perms, Long refundLimit) {
        return roles.save(new Role(id, tenant, key, name, true, perms, refundLimit));
    }

    private void emp(String id, String name, boolean minor, String pin) {
        String email = id.replace("emp_", "") + "@pecanlodge.example";
        Employee e = new Employee(id, TENANT, name, email, null, EmployeeStatus.ACTIVE, LocalDate.now().minusYears(1), minor);
        if (pin != null) e.setPinHash(PinService.hashForSeed(pin));
        employees.save(e);
    }

    private void grant(String employeeId, Role role, ScopeType type, String scopeId, String tenant) {
        assignments.save(new RoleAssignment("ra_" + employeeId + "_" + role.getSystemKey(), tenant, employeeId,
                role.getId(), type, scopeId, "seed", null, "demo data"));
    }

    private void rate(String employeeId, String job, long cents, LocalDate from) {
        rates.save(new PayRate("rate_" + employeeId + "_" + job, TENANT, employeeId, job, cents, from, "seed"));
    }

    /** Creates clock-in, optional break, clock-out punches and the day's timecard. */
    private void workedShift(String employeeId, Location loc, LocalDate day, LocalTime start, int workedMinutes,
                             int breakMinutes, String job, boolean approved) {
        ZonedDateTime in = day.atTime(start).atZone(loc.zone());
        Instant tIn = in.toInstant();
        punch(employeeId, loc, PunchType.CLOCK_IN, tIn, job, day);
        Instant cursor = tIn;
        if (breakMinutes > 0) {
            Instant bStart = tIn.plus(Duration.ofMinutes(workedMinutes / 2));
            Instant bEnd = bStart.plus(Duration.ofMinutes(breakMinutes));
            punch(employeeId, loc, PunchType.BREAK_START, bStart, job, day);
            punch(employeeId, loc, PunchType.BREAK_END, bEnd, job, day);
            cursor = bEnd.minus(Duration.ofMinutes(workedMinutes / 2));
        }
        punch(employeeId, loc, PunchType.CLOCK_OUT, cursor.plus(Duration.ofMinutes(workedMinutes)), job, day);
        TimecardState tc = new TimecardState(TENANT, employeeId, loc.getId(), day);
        if (approved) tc.approve(loc.getId().equals(LOS_ANGELES) ? "emp_gm_luis" : "emp_gm_tanya");
        timecards.save(tc);
    }

    private void punch(String employeeId, Location loc, PunchType type, Instant at, String job, LocalDate day) {
        punchSeq++;
        punches.save(new Punch("pch_seed_" + punchSeq, TENANT, employeeId, loc.getId(), type, at, PunchSource.POS,
                employeeId, "dev_pos_1", job, "seed:" + punchSeq, null, day, ""));
    }
}
