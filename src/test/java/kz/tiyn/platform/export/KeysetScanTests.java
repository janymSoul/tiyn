package kz.tiyn.platform.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kz.tiyn.platform.export.KeysetScan.Position;
import org.junit.jupiter.api.Test;

class KeysetScanTests {

    private record Row(Instant sortKey, UUID id) {}

    private static List<Row> rows(int count) {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Row(base.minusSeconds(i), UUID.randomUUID()));
        }
        return rows;
    }

    private static List<Row> after(List<Row> all, Position after, int limit) {
        int start = 0;
        if (after != null) {
            while (!all.get(start).id().equals(after.id())) {
                start++;
            }
            start++;
        }
        return all.subList(Math.min(start, all.size()), Math.min(start + limit, all.size()));
    }

    private static List<Row> scanAll(List<Row> all, int pageSize, List<Integer> pageSizes) {
        List<Row> seen = new ArrayList<>();
        new KeysetScan(pageSize)
                .scan(
                        (position, limit) -> after(all, position, limit),
                        row -> new Position(row.sortKey(), row.id()),
                        page -> {
                            pageSizes.add(page.size());
                            seen.addAll(page);
                        });
        return seen;
    }

    @Test
    void everyRowIsVisitedOnceInOrderAcrossPages() {
        List<Row> all = rows(5);
        List<Integer> pageSizes = new ArrayList<>();

        assertThat(scanAll(all, 2, pageSizes)).containsExactlyElementsOf(all);
        assertThat(pageSizes).containsExactly(2, 2, 1);
    }

    @Test
    void anExactMultipleOfThePageSizeEndsWithoutAnExtraSinkCall() {
        List<Row> all = rows(4);
        List<Integer> pageSizes = new ArrayList<>();

        assertThat(scanAll(all, 2, pageSizes)).containsExactlyElementsOf(all);
        assertThat(pageSizes).containsExactly(2, 2);
    }

    @Test
    void anEmptySourceNeverCallsTheSink() {
        List<Integer> pageSizes = new ArrayList<>();

        assertThat(scanAll(List.of(), 3, pageSizes)).isEmpty();
        assertThat(pageSizes).isEmpty();
    }

    @Test
    void theNextFetchStartsAfterTheLastRowOfThePreviousPage() {
        List<Row> all = rows(3);
        List<Position> requested = new ArrayList<>();

        new KeysetScan(2)
                .scan(
                        (position, limit) -> {
                            requested.add(position);
                            return after(all, position, limit);
                        },
                        row -> new Position(row.sortKey(), row.id()),
                        page -> {});

        assertThat(requested)
                .containsExactly(
                        null, new Position(all.get(1).sortKey(), all.get(1).id()));
    }
}
