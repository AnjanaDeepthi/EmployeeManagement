package com.restaurant.labor.common;

import com.restaurant.labor.employee.Employee;
import com.restaurant.labor.employee.EmployeeRepository;
import com.restaurant.labor.employee.EmployeeStatus;
import com.restaurant.labor.employee.PinService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Resolves and validates the caller for every /v1 request (except PIN sign-in, which only needs a tenant). */
@Component
public class CallerInterceptor implements HandlerInterceptor {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String EMPLOYEE_HEADER = "X-Employee-Id";
    public static final String PIN_SESSION_HEADER = "X-Pin-Session";

    private final EmployeeRepository employees;
    private final PinService pinService;

    public CallerInterceptor(EmployeeRepository employees, PinService pinService) {
        this.employees = employees;
        this.pinService = pinService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenantId = request.getHeader(TENANT_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            throw unauthenticated("Missing X-Tenant-Id header.");
        }
        if (request.getRequestURI().equals("/v1/pos/pin_sessions")) {
            Caller.set(new Caller(tenantId, null, false));
            return true;
        }

        String employeeId;
        boolean viaPin = false;
        String pinSession = request.getHeader(PIN_SESSION_HEADER);
        if (pinSession != null && !pinSession.isBlank()) {
            employeeId = pinService.resolveSession(tenantId, pinSession);
            viaPin = true;
        } else {
            employeeId = request.getHeader(EMPLOYEE_HEADER);
        }
        if (employeeId == null || employeeId.isBlank()) {
            throw unauthenticated("Missing X-Employee-Id (or X-Pin-Session) header.");
        }
        Employee caller = employees.findByIdAndTenantId(employeeId, tenantId)
                .orElseThrow(() -> unauthenticated("Unknown employee for this tenant."));
        boolean acceptingInvite = caller.getStatus() == EmployeeStatus.INVITED
                && request.getRequestURI().endsWith("/invitation/acceptance");
        if (caller.getStatus() != EmployeeStatus.ACTIVE && !acceptingInvite) {
            throw unauthenticated("Employee is not active.");
        }
        Caller.set(new Caller(tenantId, employeeId, viaPin));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Caller.clear();
    }

    private static ApiException unauthenticated(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", detail);
    }
}
