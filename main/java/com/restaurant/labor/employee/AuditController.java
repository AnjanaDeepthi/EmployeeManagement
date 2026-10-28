package com.restaurant.labor.employee;

import com.restaurant.labor.common.AuditEvent;
import com.restaurant.labor.common.AuditRepository;
import com.restaurant.labor.common.Caller;
import com.restaurant.labor.common.CursorPage;
import com.restaurant.labor.employee.EmployeeDtos.AuditResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Limit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "5. Audit")
@RestController
public class AuditController {

    private static final List<String> ROLE_ACTIONS = List.of(
            "role.created", "role.updated", "role_assignment.granted", "role_assignment.revoked",
            "employee.invited", "employee.activated", "employee.updated", "employee.terminated");

    private final AuditRepository audit;
    private final AuthorizationService authz;

    public AuditController(AuditRepository audit, AuthorizationService authz) {
        this.audit = audit;
        this.authz = authz;
    }

    @Operation(summary = "Audit trail of role and employee changes (cursor paging)")
    @GetMapping("/v1/audit/role_changes")
    public CursorPage<AuditResponse> roleChanges(@RequestParam(required = false) String cursor,
                                                 @RequestParam(required = false) Integer limit) {
        authz.require(Permission.AUDIT_READ, null);
        int n = CursorPage.clampLimit(limit);
        List<AuditEvent> rows = audit.findByTenantIdAndActionInAndIdGreaterThanOrderByIdAsc(
                Caller.current().tenantId(), ROLE_ACTIONS, CursorPage.decode(cursor), Limit.of(n + 1));
        return CursorPage.of(rows, n, AuditEvent::getId, a -> new AuditResponse(a.getId(), a.getActorId(),
                a.getAction(), a.getTargetType(), a.getTargetId(), a.getDetails(), a.getOccurredAt()));
    }
}
