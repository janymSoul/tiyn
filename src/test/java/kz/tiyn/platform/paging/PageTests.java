package kz.tiyn.platform.paging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PageTests {

    private static final Instant AT = Instant.parse("2026-11-07T10:00:00Z");
    private static final String HASH = Cursor.hashOf();

    private record Row(UUID id) {}

    private static Cursor cursorOf(Row row) {
        return new Cursor(AT, row.id(), HASH);
    }

    private static List<Row> rows(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new Row(UUID.randomUUID()))
                .toList();
    }

    @Test
    void anExtraRowMeansAnotherPageAndTheCursorPointsAtTheLastReturnedRow() {
        List<Row> fetched = rows(4);

        Page<Row> page = Page.of(fetched, new PageRequest(3, null), PageTests::cursorOf);

        assertThat(page.items()).containsExactlyElementsOf(fetched.subList(0, 3));
        assertThat(Cursor.decode(page.nextCursor()).id())
                .isEqualTo(fetched.get(2).id());
    }

    @Test
    void exactlyALimitOfRowsIsTheLastPage() {
        Page<Row> page = Page.of(rows(3), new PageRequest(3, null), PageTests::cursorOf);

        assertThat(page.items()).hasSize(3);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void noRowsIsAnEmptyLastPage() {
        Page<Row> page = Page.of(List.of(), new PageRequest(3, null), PageTests::cursorOf);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void mappingKeepsTheCursor() {
        Page<Row> page = Page.of(rows(4), new PageRequest(3, null), PageTests::cursorOf);

        Page<UUID> mapped = page.map(Row::id);

        assertThat(mapped.items())
                .containsExactlyElementsOf(page.items().stream().map(Row::id).toList());
        assertThat(mapped.nextCursor()).isEqualTo(page.nextCursor());
    }

    @Test
    void theLastPageSerialisesWithoutANextCursor() throws Exception {
        ObjectMapper json = new ObjectMapper();

        assertThat(json.writeValueAsString(new Page<>(List.<String>of(), null))).isEqualTo("{\"items\":[]}");
        assertThat(json.writeValueAsString(new Page<>(List.of("a"), "abc")))
                .isEqualTo("{\"items\":[\"a\"],\"nextCursor\":\"abc\"}");
    }
}
