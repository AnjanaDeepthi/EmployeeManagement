package com.restaurant.labor.employee;

import com.restaurant.labor.common.CursorPage;
import com.restaurant.labor.common.ETags;
import com.restaurant.labor.employee.EmployeeDtos.EmployeeResponse;
import com.restaurant.labor.employee.EmployeeDtos.InviteEmployeeRequest;
import com.restaurant.labor.employee.EmployeeDtos.SetPinRequest;
import com.restaurant.labor.employee.EmployeeDtos.TerminationRequest;
import com.restaurant.labor.employee.EmployeeDtos.UpdateEmployeeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "1. Employees")
@RestController
@RequestMapping("/v1/employees")
public class EmployeeController {

    private final EmployeeService service;
    private final PinService pins;

    public EmployeeController(EmployeeService service, PinService pins) {
        this.service = service;
        this.pins = pins;
    }

    @Operation(summary = "Invite an employee (idempotent by email: returns the existing employee with 200)")
    @PostMapping
    public ResponseEntity<EmployeeResponse> invite(@Valid @RequestBody InviteEmployeeRequest req) {
        EmployeeService.InviteResult r = service.invite(req);
        Employee e = r.employee();
        if (!r.created()) {
            return ResponseEntity.ok().eTag(ETags.of(e.getVersion())).body(EmployeeResponse.of(e));
        }
        return ResponseEntity.created(URI.create("/v1/employees/" + e.getId()))
                .eTag(ETags.of(e.getVersion())).body(EmployeeResponse.of(e));
    }

    @Operation(summary = "List employees, filtered by location, role and status (cursor paging)")
    @GetMapping
    public CursorPage<EmployeeResponse> list(@RequestParam(name = "location_id", required = false) String locationId,
                                             @RequestParam(name = "role_id", required = false) String roleId,
                                             @RequestParam(required = false) EmployeeStatus status,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) Integer limit) {
        return service.list(locationId, roleId, status, cursor, limit);
    }

    @Operation(summary = "Get an employee ('me' is an alias for the caller)")
    @GetMapping("/{employeeId}")
    public ResponseEntity<EmployeeResponse> get(@PathVariable String employeeId) {
        Employee e = service.get(employeeId);
        return ResponseEntity.ok().eTag(ETags.of(e.getVersion())).body(EmployeeResponse.of(e));
    }

    @Operation(summary = "Update profile fields (If-Match required). Employees may edit their own name and phone.")
    @PatchMapping("/{employeeId}")
    public ResponseEntity<EmployeeResponse> update(@PathVariable String employeeId,
                                                   @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                   @Valid @RequestBody UpdateEmployeeRequest req) {
        Employee e = service.update(employeeId, req, ifMatch);
        return ResponseEntity.ok().eTag(ETags.of(e.getVersion())).body(EmployeeResponse.of(e));
    }

    @Operation(summary = "Accept your own invitation (INVITED -> ACTIVE)")
    @PostMapping("/{employeeId}/invitation/acceptance")
    public EmployeeResponse acceptInvitation(@PathVariable String employeeId) {
        return EmployeeResponse.of(service.acceptInvitation(employeeId));
    }

    @Operation(summary = "Terminate: revokes roles and POS sessions immediately; the record is kept")
    @PostMapping("/{employeeId}/termination")
    public EmployeeResponse terminate(@PathVariable String employeeId, @Valid @RequestBody TerminationRequest req) {
        return EmployeeResponse.of(service.terminate(employeeId, req));
    }

    @Operation(summary = "Set your own POS PIN (4-6 digits, stored hashed)")
    @PutMapping("/{employeeId}/pin")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPin(@PathVariable String employeeId, @Valid @RequestBody SetPinRequest req) {
        pins.setOwnPin(EmployeeService.resolve(employeeId), req.pin());
    }
}
