package kz.tiyn.identity.family;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.tiyn.support.PagedEndpointContract;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Person;
import org.junit.jupiter.api.Test;

class JoinRequestPagingTests extends PagedEndpointContract {

    @Override
    protected Owner newOwner() throws Exception {
        Person parent = new TaskFixtures(mvc, json).unjoinedFamily().family().parent();
        return new Owner(parent.bearer(), parent.id(), null);
    }

    @Override
    protected UUID insertRow(Owner owner, Instant sortKey) throws Exception {
        Person teen = new TaskFixtures(mvc, json).unlinkedTeen();
        return insertRequest(owner.id(), teen.id(), "PENDING", sortKey);
    }

    private UUID insertRequest(UUID parentId, UUID teenId, String state, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO identity.join_requests (id, parent_id, teen_id, state, decided_at, created_at)
                VALUES (?, ?, ?, ?, CASE WHEN ? = 'PENDING' THEN NULL ELSE now() END, ?)
                """,
                id,
                parentId,
                teenId,
                state,
                state,
                Timestamp.from(createdAt));
        return id;
    }

    @Override
    protected String path() {
        return "/links/requests";
    }

    @Override
    protected String orderedIdsSql() {
        return "SELECT id FROM identity.join_requests WHERE parent_id = ? AND state = 'PENDING'"
                + " ORDER BY created_at DESC, id DESC";
    }

    @Test
    void decidedRequestsNeverAppearOnAnyPage() throws Exception {
        Owner owner = newOwner();
        List<Instant> times = descendingTimestamps(6);
        for (int i = 0; i < 6; i++) {
            Person teen = new TaskFixtures(mvc, json).unlinkedTeen();
            insertRequest(owner.id(), teen.id(), i % 2 == 0 ? "PENDING" : "DECLINED", times.get(i));
        }

        List<UUID> seen =
                walk(owner, null, 2).stream().flatMap(p -> idsOf(p).stream()).toList();

        assertThat(seen).hasSize(3).isEqualTo(expectedOrder(owner));
    }
}
