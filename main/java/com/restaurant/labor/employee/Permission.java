package com.restaurant.labor.employee;

import java.util.Arrays;
import java.util.Optional;

/**
 * System-defined, fine-grained permissions. The string codes are part of the API contract:
 * never rename them; deprecate and add new ones instead.
 */
public enum Permission {
    EMPLOYEES_READ("employees:read", "View employees and their role assignments"),
    EMPLOYEES_MANAGE("employees:manage", "Invite, edit, terminate employees and grant roles"),
    ROLES_MANAGE("roles:manage", "Create and edit custom roles"),
    AUDIT_READ("audit:read", "Read the role-change audit trail"),
    SCHEDULES_MANAGE("schedules:manage", "Build and publish schedules"),
    REQUESTS_APPROVE("requests:approve", "Approve time-off requests and shift swaps"),
    PUNCHES_WRITE_OTHERS("punches:write_others", "Record punches on behalf of another employee (manager override)"),
    TIMECARDS_READ("timecards:read", "View timecards of other employees"),
    TIMECARDS_ADJUST("timecards:adjust", "Correct punches on timecards"),
    TIMECARDS_APPROVE("timecards:approve", "Approve timecards"),
    PAY_PERIODS_MANAGE("pay_periods:manage", "Create and lock pay periods"),
    PAYROLL_READ("payroll:read", "See pay rates and wage totals"),
    PAY_RATES_MANAGE("pay_rates:manage", "Set employee pay rates"),
    PAYROLL_EXPORT("payroll:export", "Export locked pay periods to the payroll provider"),
    REFUNDS_CREATE("refunds:create", "Issue refunds (up to the role's refund limit)");

    private final String code;
    private final String description;

    Permission(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() { return code; }
    public String description() { return description; }

    public static Optional<Permission> fromCode(String code) {
        return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst();
    }
}
