package com.restaurant.labor.common;

import org.springframework.http.HttpStatus;

/** Optimistic concurrency helpers: ETag = the entity's version; writes must send If-Match. */
public final class ETags {

    private ETags() {}

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * 428 when If-Match is missing (no silent unconditional writes),
     * 412 when it doesn't match the current version (someone else changed it first).
     */
    public static void requireMatch(String ifMatch, long currentVersion) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "precondition_required",
                    "This endpoint requires an If-Match header with the resource's current ETag.");
        }
        String expected = of(currentVersion);
        String sent = ifMatch.trim().replaceFirst("^W/", "");
        if (!sent.equals(expected) && !sent.equals("*")) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "version_conflict",
                    "The resource was changed by someone else. Re-read it and re-apply your change.")
                    .with("your_version", sent)
                    .with("current_version", expected);
        }
    }
}
