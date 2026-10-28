package com.restaurant.labor.common;

import java.security.SecureRandom;

/**
 * Opaque, prefixed, roughly time-ordered IDs (e.g. {@code pch_lq3k9x2a7fz0b1}).
 * The prefix makes logs readable; time ordering keeps cursor paging by ID close to creation order.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private Ids() {}

    public static String next(String prefix) {
        StringBuilder sb = new StringBuilder(prefix).append('_');
        String time = Long.toString(System.currentTimeMillis(), 36);
        sb.append("0".repeat(Math.max(0, 9 - time.length()))).append(time);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
