package kz.tiyn.platform.paging;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.function.Function;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Page<T>(List<T> items, String nextCursor) {

    public static <T> Page<T> of(List<T> fetched, PageRequest request, Function<T, Cursor> cursorOf) {
        if (fetched.size() <= request.limit()) {
            return new Page<>(List.copyOf(fetched), null);
        }
        List<T> items = List.copyOf(fetched.subList(0, request.limit()));
        return new Page<>(items, cursorOf.apply(items.get(items.size() - 1)).encode());
    }

    public <R> Page<R> map(Function<T, R> mapper) {
        return new Page<>(items.stream().map(mapper).toList(), nextCursor);
    }
}
