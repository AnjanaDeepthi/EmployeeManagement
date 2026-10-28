package com.restaurant.labor.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * A page of results plus an opaque cursor. The cursor encodes the last ID returned (keyset paging),
 * so pages stay stable while new rows are being inserted.
 */
public record CursorPage<T>(List<T> data, String nextCursor, boolean hasMore) {

    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_LIMIT = 100;

    /** @param rows up to limit + 1 rows; the extra row only tells us whether there is another page. */
    public static <E, T> CursorPage<T> of(List<E> rows, int limit, Function<E, String> idOf, Function<E, T> mapper) {
        boolean more = rows.size() > limit;
        List<E> page = more ? rows.subList(0, limit) : rows;
        String cursor = more ? encode(idOf.apply(page.get(page.size() - 1))) : null;
        return new CursorPage<>(page.stream().map(mapper).toList(), cursor, more);
    }

    public static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    public static String encode(String lastId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(lastId.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns "" for the first page so that "id > afterId" matches everything. */
    public static String decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return "";
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "invalid_cursor", "The cursor is not valid.");
        }
    }
}
