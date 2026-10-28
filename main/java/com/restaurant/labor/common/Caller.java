package com.restaurant.labor.common;

/**
 * Who is calling. Kept deliberately simple for a demo: the tenant and employee come from request headers
 * (X-Tenant-Id plus X-Employee-Id, or X-Pin-Session from a POS PIN sign-in).
 * In production these would come from a verified OAuth token, never from client-controlled headers.
 */
public record Caller(String tenantId, String employeeId, boolean viaPinSession) {

    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    public static Caller current() {
        Caller c = CURRENT.get();
        if (c == null) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "unauthenticated",
                    "Send X-Tenant-Id and X-Employee-Id (or X-Pin-Session) headers.");
        }
        return c;
    }

    static void set(Caller caller) { CURRENT.set(caller); }
    static void clear() { CURRENT.remove(); }
}
