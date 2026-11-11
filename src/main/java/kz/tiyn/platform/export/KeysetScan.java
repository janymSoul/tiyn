package kz.tiyn.platform.export;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

public final class KeysetScan {

    private final int pageSize;

    public KeysetScan(int pageSize) {
        this.pageSize = pageSize;
    }

    public record Position(Instant sortKey, UUID id) {}

    @FunctionalInterface
    public interface PageSource<T> {

        List<T> fetch(Position after, int limit);
    }

    public <T> void scan(PageSource<T> source, Function<T, Position> positionOf, Consumer<List<T>> pageSink) {
        Position after = null;
        while (true) {
            List<T> page = source.fetch(after, pageSize);
            if (!page.isEmpty()) {
                pageSink.accept(page);
            }
            if (page.size() < pageSize) {
                return;
            }
            after = positionOf.apply(page.get(page.size() - 1));
        }
    }
}
