package com.restaurant.labor.employee;

import com.restaurant.labor.common.ApiException;
import com.restaurant.labor.common.AuditService;
import com.restaurant.labor.common.Caller;
import com.restaurant.labor.common.Ids;
import com.restaurant.labor.location.LocationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * POS PIN sign-in. PINs are short, so they are only accepted together with a location/device,
 * are stored hashed (PBKDF2), and lock out after repeated failures.
 */
@Service
public class PinService {

    private static final Duration SESSION_TTL = Duration.ofHours(12);

    private final EmployeeRepository employees;
    private final PinSessionRepository sessions;
    private final LocationRepository locations;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final int maxAttempts;
    private final int lockoutMinutes;

    public PinService(EmployeeRepository employees, PinSessionRepository sessions, LocationRepository locations,
                      AuthorizationService authz, AuditService audit,
                      @Value("${labor.pin.max-attempts:5}") int maxAttempts,
                      @Value("${labor.pin.lockout-minutes:15}") int lockoutMinutes) {
        this.employees = employees;
        this.sessions = sessions;
        this.locations = locations;
        this.authz = authz;
        this.audit = audit;
        this.maxAttempts = maxAttempts;
        this.lockoutMinutes = lockoutMinutes;
    }

    /** Employees set their own PIN. */
    @Transactional
    public void setOwnPin(String employeeId, String pin) {
        Caller c = Caller.current();
        if (!c.employeeId().equals(employeeId)) {
            throw ApiException.forbidden("not_self", "Employees can only set their own PIN.");
        }
        Employee e = employees.require(employeeId, c.tenantId());
        e.setPinHash(hash(pin));
        e.setFailedPinAttempts(0);
        e.setPinLockedUntil(null);
        audit.record("employee.pin_set", "employee", employeeId, null);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public PinSession signIn(String employeeId, String pin, String locationId, String deviceId) {
        String tenantId = Caller.current().tenantId();
        locations.require(locationId, tenantId);
        Employee e = employees.findByIdAndTenantId(employeeId, tenantId)
                .orElseThrow(() -> invalidPin());
        if (e.getStatus() != EmployeeStatus.ACTIVE || e.getPinHash() == null) {
            throw invalidPin();
        }
        Instant now = Instant.now();
        if (e.getPinLockedUntil() != null && e.getPinLockedUntil().isAfter(now)) {
            throw new ApiException(HttpStatus.LOCKED, "pin_locked", "Too many wrong PINs. Ask a manager or try later.")
                    .with("locked_until", e.getPinLockedUntil());
        }
        if (!matches(pin, e.getPinHash())) {
            int attempts = e.getFailedPinAttempts() + 1;
            e.setFailedPinAttempts(attempts);
            if (attempts >= maxAttempts) {
                e.setPinLockedUntil(now.plus(Duration.ofMinutes(lockoutMinutes)));
                e.setFailedPinAttempts(0);
            }
            throw invalidPin();
        }
        if (!authz.isAssignedAt(tenantId, employeeId, locationId)) {
            throw ApiException.forbidden("not_assigned_to_location", "This employee does not work at this location.");
        }
        e.setFailedPinAttempts(0);
        e.setPinLockedUntil(null);
        return sessions.save(new PinSession(Ids.next("pins"), tenantId, employeeId, locationId, deviceId, now.plus(SESSION_TTL)));
    }

    /** Used by the caller interceptor for requests that carry X-Pin-Session. */
    public String resolveSession(String tenantId, String token) {
        PinSession s = sessions.findByTokenAndTenantId(token, tenantId)
                .filter(x -> x.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "PIN session expired or unknown."));
        return s.getEmployeeId();
    }

    @Transactional
    public void endAllSessions(String tenantId, String employeeId) {
        sessions.deleteByTenantIdAndEmployeeId(tenantId, employeeId);
    }

    private static ApiException invalidPin() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_pin", "Employee or PIN is not correct.");
    }

    // --- hashing --------------------------------------------------------------------------------

    static String hash(String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(pbkdf2(pin, salt));
    }

    static boolean matches(String pin, String stored) {
        String[] parts = stored.split(":");
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] expected = Base64.getDecoder().decode(parts[1]);
        return MessageDigest.isEqual(expected, pbkdf2(pin, salt));
    }

    private static byte[] pbkdf2(String pin, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, 120_000, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PIN hashing unavailable", e);
        }
    }

    /** Exposed for the demo data seeder. */
    public static String hashForSeed(String pin) {
        return hash(pin);
    }
}
