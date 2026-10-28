package com.restaurant.labor.employee;

import com.restaurant.labor.employee.EmployeeDtos.PinSessionRequest;
import com.restaurant.labor.employee.EmployeeDtos.PinSessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "4. POS sign-in")
@RestController
public class PosController {

    private final PinService pins;

    public PosController(PinService pins) {
        this.pins = pins;
    }

    @Operation(summary = "Sign in on a POS terminal with a PIN. Only needs X-Tenant-Id. Locks out after 5 failures.")
    @PostMapping("/v1/pos/pin_sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public PinSessionResponse signIn(@Valid @RequestBody PinSessionRequest req) {
        PinSession s = pins.signIn(req.employeeId(), req.pin(), req.locationId(), req.deviceId());
        return new PinSessionResponse(s.getToken(), s.getEmployeeId(), s.getLocationId(), s.getExpiresAt());
    }
}
