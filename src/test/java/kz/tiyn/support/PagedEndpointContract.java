package kz.tiyn.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import kz.tiyn.platform.paging.Cursor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
public abstract class PagedEndpointContract {

    public record Owner(String bearer, UUID id, Object context) {}

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    protected abstract Owner newOwner() throws Exception;

    protected abstract UUID insertRow(Owner owner, Instant sortKey) throws Exception;

    protected abstract String path();

    protected abstract String orderedIdsSql();

    protected String otherFilter() {
        return null;
    }

    protected final ResultActions request(Owner owner, String query) throws Exception {
        String url = path() + (query == null || query.isEmpty() ? "" : "?" + query);
        return mvc.perform(get(url).header("Authorization", owner.bearer()));
    }

    protected final JsonNode page(Owner owner, String query) throws Exception {
        return json.readTree(request(owner, query)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    protected static List<UUID> idsOf(JsonNode page) {
        return IntStream.range(0, page.get("items").size())
                .mapToObj(
                        i -> UUID.fromString(page.get("items").get(i).get("id").asText()))
                .toList();
    }

    protected final List<UUID> expectedOrder(Owner owner) {
        return jdbc.queryForList(orderedIdsSql(), UUID.class, owner.id());
    }

    protected final List<JsonNode> walk(Owner owner, String filters, int limit) throws Exception {
        List<JsonNode> pages = new ArrayList<>();
        String prefix = (filters == null || filters.isEmpty() ? "" : filters + "&") + "limit=" + limit;
        JsonNode current = page(owner, prefix);
        pages.add(current);
        while (current.hasNonNull("nextCursor")) {
            assertThat(pages).hasSizeLessThan(200);
            current =
                    page(owner, prefix + "&cursor=" + current.get("nextCursor").asText());
            pages.add(current);
        }
        return pages;
    }

    protected final List<Instant> descendingTimestamps(int count) {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(1, ChronoUnit.HOURS);
        return IntStream.range(0, count)
                .mapToObj(i -> base.minus(i, ChronoUnit.MINUTES))
                .toList();
    }

    @Test
    void walkingAllPagesReturnsEveryRowOnceInOrder() throws Exception {
        Owner owner = newOwner();
        for (Instant at : descendingTimestamps(7)) {
            insertRow(owner, at);
        }

        List<JsonNode> pages = walk(owner, null, 3);

        assertThat(pages).hasSize(3);
        assertThat(pages.stream().map(p -> p.get("items").size())).containsExactly(3, 3, 1);
        assertThat(pages.stream().flatMap(p -> idsOf(p).stream()).toList()).isEqualTo(expectedOrder(owner));
        assertThat(expectedOrder(owner)).hasSize(7);
        assertThat(pages.get(0).hasNonNull("nextCursor")).isTrue();
        assertThat(pages.get(2).has("nextCursor")).isFalse();
    }

    @Test
    void aPageHoldingExactlyTheRemainingRowsIsTheLastPage() throws Exception {
        Owner owner = newOwner();
        for (Instant at : descendingTimestamps(4)) {
            insertRow(owner, at);
        }

        List<JsonNode> pages = walk(owner, null, 2);

        assertThat(pages).hasSize(2);
        assertThat(pages.get(1).get("items")).hasSize(2);
        assertThat(pages.get(1).has("nextCursor")).isFalse();
    }

    @Test
    void rowsInsertedBetweenPagesNeitherAppearLaterNorCauseRepeats() throws Exception {
        Owner owner = newOwner();
        for (Instant at : descendingTimestamps(5)) {
            insertRow(owner, at);
        }
        List<UUID> before = expectedOrder(owner);

        JsonNode first = page(owner, "limit=2");
        UUID inserted = insertRow(owner, Instant.now().truncatedTo(ChronoUnit.MICROS));
        List<UUID> seen = new ArrayList<>(idsOf(first));
        JsonNode current = first;
        while (current.hasNonNull("nextCursor")) {
            current = page(owner, "limit=2&cursor=" + current.get("nextCursor").asText());
            seen.addAll(idsOf(current));
        }

        assertThat(seen).isEqualTo(before);
        assertThat(seen).doesNotContain(inserted);
        assertThat(expectedOrder(owner)).hasSize(6);
    }

    @Test
    void rowsWithTheSameSortKeyAreSplitAcrossAPageBoundaryWithoutLossOrRepeats() throws Exception {
        Owner owner = newOwner();
        Instant sameInstant = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(1, ChronoUnit.HOURS);
        for (int i = 0; i < 5; i++) {
            insertRow(owner, sameInstant);
        }

        List<JsonNode> pages = walk(owner, null, 2);
        List<UUID> seen = pages.stream().flatMap(p -> idsOf(p).stream()).toList();

        assertThat(pages.stream().map(p -> p.get("items").size())).containsExactly(2, 2, 1);
        assertThat(seen).isEqualTo(expectedOrder(owner));
        assertThat(new HashSet<>(seen)).hasSize(5);
    }

    @Test
    void anEmptyListIsJustAnEmptyItemsArray() throws Exception {
        Owner owner = newOwner();

        String body = request(owner, null)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(json.readTree(body)).isEqualTo(json.readTree("{\"items\":[]}"));
        assertThat(body).doesNotContain("nextCursor");
    }

    @Test
    void theDefaultPageHoldsFiftyRows() throws Exception {
        Owner owner = newOwner();
        for (Instant at : descendingTimestamps(51)) {
            insertRow(owner, at);
        }

        JsonNode first = page(owner, null);

        assertThat(first.get("items")).hasSize(50);
        assertThat(first.hasNonNull("nextCursor")).isTrue();
        assertThat(page(owner, "limit=100").get("items")).hasSize(51);
    }

    @Test
    void aCursorReusedWithDifferentFiltersOrGarbageIsInvalidCursor() throws Exception {
        Owner owner = newOwner();
        for (Instant at : descendingTimestamps(3)) {
            insertRow(owner, at);
        }
        String cursor = otherFilter() == null
                ? new Cursor(Instant.now(), UUID.randomUUID(), Cursor.hashOf("another list")).encode()
                : page(owner, "limit=1").get("nextCursor").asText();
        String filter = otherFilter() == null ? "" : otherFilter() + "&";

        request(owner, filter + "cursor=" + cursor)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_cursor"));
        request(owner, "cursor=garbage")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_cursor"));
        request(owner, "cursor=" + cursor.substring(0, cursor.length() / 2))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_cursor"));
    }

    @Test
    void aLimitOutsideOneToOneHundredIsABadRequest() throws Exception {
        Owner owner = newOwner();

        for (String limit : List.of("0", "101", "-1", "abc")) {
            request(owner, "limit=" + limit)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"));
        }
        request(owner, "limit=1").andExpect(status().isOk());
        request(owner, "limit=100").andExpect(status().isOk());
    }

    @Test
    void anotherOwnerNeverSeesTheRowsAndACursorFromOneOwnerLeaksNothingToTheOther() throws Exception {
        Owner alice = newOwner();
        Owner bob = newOwner();
        for (Instant at : descendingTimestamps(4)) {
            insertRow(alice, at);
            insertRow(bob, at.minusSeconds(30));
        }
        String alicesCursor = page(alice, "limit=2").get("nextCursor").asText();

        List<UUID> bobsWalk =
                walk(bob, null, 2).stream().flatMap(p -> idsOf(p).stream()).toList();
        List<UUID> viaAlicesCursor = idsOf(page(bob, "cursor=" + alicesCursor));

        assertThat(bobsWalk).isEqualTo(expectedOrder(bob)).doesNotContainAnyElementsOf(expectedOrder(alice));
        assertThat(expectedOrder(bob)).containsAll(viaAlicesCursor);
        assertThat(viaAlicesCursor).doesNotContainAnyElementsOf(expectedOrder(alice));
    }
}
