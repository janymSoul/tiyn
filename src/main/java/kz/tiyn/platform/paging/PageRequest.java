package kz.tiyn.platform.paging;

import java.util.Optional;

public record PageRequest(int limit, Cursor cursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    public int fetchSize() {
        return limit + 1;
    }

    public Optional<Cursor> after(String filterHash) {
        if (cursor != null && !cursor.filterHash().equals(filterHash)) {
            throw Cursor.invalid();
        }
        return Optional.ofNullable(cursor);
    }
}
