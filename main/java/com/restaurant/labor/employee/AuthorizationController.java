package com.restaurant.labor.employee;

import com.restaurant.labor.common.ApiException;
import com.restaurant.labor.common.Caller;
import com.restaurant.labor.employee.EmployeeDtos.AuthorizationCheckRequest;
import com.restaurant.labor.employee.EmployeeDtos.AuthorizationCheckResponse;
import com.restaurant.labor.employee.EmployeeDtos.AuthorizationCheckResult;
import com.restaurant.labor.employee.EmployeeDtos.EffectivePermissionsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "3. Authorization")
@RestController
public class AuthorizationController {

    private final AuthorizationService authz;
    private final EmployeeRepository employees;

    public AuthorizationController(AuthorizationService authz, EmployeeRepository employees) {
        this.authz = authz;
        this.employees = employees;
    }

    @Operation(summary = "Resolved permissions for an employee at a location (omit location_id for tenant-wide)")
    @GetMapping("/v1/employees/{employeeId}/effective_permissions")
    public EffectivePermissionsResponse effective(@PathVariable String employeeId,
                                                  @RequestParam(name = "location_id", required = false) String locationId) {
        Caller c = Caller.current();
        String id = EmployeeService.resolve(employeeId);
        employees.require(id, c.tenantId());
        if (!id.equals(c.employeeId())) {
            authz.requireOverEmployee(Permission.EMPLOYEES_READ, id);
        }
        AuthorizationService.Effective e = authz.effective(c.tenantId(), id, locationId);
        return new EffectivePermissionsResponse(id, locationId, e.permissions(), e.refundLimitCents());
    }

    @Operation(summary = "Batch check: can subject X do Y at location Z? (up to 100 checks)")
    @PostMapping("/v1/authorization/checks")
    public AuthorizationCheckResponse check(@Valid @RequestBody AuthorizationCheckRequest req) {
        Caller c = Caller.current();
        boolean onlySelf = req.checks().stream().allMatch(ch -> ch.employeeId().equals(c.employeeId()));
        if (!onlySelf) {
            // Checking other people's permissions is itself privileged.
            authz.require(Permission.EMPLOYEES_READ, null);
        }
        return new AuthorizationCheckResponse(req.checks().stream().map(ch -> {
            Permission p = Permission.fromCode(ch.permission()).orElseThrow(() ->
                    ApiException.unprocessable("unknown_permission", "Unknown permission: " + ch.permission()));
            boolean allowed = authz.has(c.tenantId(), ch.employeeId(), p, ch.locationId());
            return new AuthorizationCheckResult(ch.employeeId(), ch.permission(), ch.locationId(), allowed);
        }).toList());
    }
}
