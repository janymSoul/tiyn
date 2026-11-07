package kz.tiyn.task.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.tiyn.support.PagedEndpointContract;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.Test;

class TaskPagingTests extends PagedEndpointContract {

    private TaskFixtures fx() {
        return new TaskFixtures(mvc, json);
    }

    @Override
    protected Owner newOwner() throws Exception {
        Family family = fx().linkedFamily();
        return new Owner(family.parent().bearer(), family.parent().id(), family);
    }

    @Override
    protected UUID insertRow(Owner owner, Instant sortKey) {
        return insertTask(owner.id(), ((Family) owner.context()).teen().id(), "ASSIGNED", sortKey);
    }

    private UUID insertTask(UUID parentId, UUID teenId, String state, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO task.tasks
                    (id, parent_id, teen_id, kind, title, reward_amount, currency, state, deadline_at, created_at)
                VALUES (?, ?, ?, 'TASK', 'Paged', 100, 'KZT', ?, now() + interval '1 day', ?)
                """,
                id,
                parentId,
                teenId,
                state,
                java.sql.Timestamp.from(createdAt));
        return id;
    }

    @Override
    protected String path() {
        return "/tasks";
    }

    @Override
    protected String orderedIdsSql() {
        return "SELECT id FROM task.tasks WHERE parent_id = ? ORDER BY created_at DESC, id DESC";
    }

    @Override
    protected String otherFilter() {
        return "state=APPROVED";
    }

    @Test
    void theStateFilterAppliesAcrossPages() throws Exception {
        Owner owner = newOwner();
        UUID teen = ((Family) owner.context()).teen().id();
        List<Instant> times = descendingTimestamps(6);
        for (int i = 0; i < 6; i++) {
            insertTask(owner.id(), teen, i % 2 == 0 ? "APPROVED" : "ASSIGNED", times.get(i));
        }

        List<UUID> approved = walk(owner, "state=APPROVED", 2).stream()
                .flatMap(p -> idsOf(p).stream())
                .toList();

        assertThat(approved).hasSize(3);
        assertThat(approved)
                .containsExactlyElementsOf(jdbc.queryForList(
                        "SELECT id FROM task.tasks WHERE parent_id = ? AND state = 'APPROVED'"
                                + " ORDER BY created_at DESC, id DESC",
                        UUID.class,
                        owner.id()));
    }

    @Test
    void theTeenFilterAppliesAcrossPages() throws Exception {
        Owner owner = newOwner();
        UUID firstTeen = ((Family) owner.context()).teen().id();
        UUID secondTeen = joinSecondTeen(owner);
        List<Instant> times = descendingTimestamps(6);
        for (int i = 0; i < 6; i++) {
            insertTask(owner.id(), i % 2 == 0 ? firstTeen : secondTeen, "ASSIGNED", times.get(i));
        }

        List<UUID> second = walk(owner, "teenId=" + secondTeen, 2).stream()
                .flatMap(p -> idsOf(p).stream())
                .toList();

        assertThat(second)
                .containsExactlyElementsOf(jdbc.queryForList(
                        "SELECT id FROM task.tasks WHERE parent_id = ? AND teen_id = ?"
                                + " ORDER BY created_at DESC, id DESC",
                        UUID.class,
                        owner.id(),
                        secondTeen));
        assertThat(second).hasSize(3);
        request(
                        owner,
                        "limit=1&cursor="
                                + page(owner, "teenId=" + secondTeen + "&limit=1")
                                        .get("nextCursor")
                                        .asText())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isUnprocessableEntity());
    }

    @Test
    void tasksOfAnUnlinkedTeenAreFilteredInTheQueryNotAfterwards() throws Exception {
        Owner owner = newOwner();
        UUID linkedTeen = ((Family) owner.context()).teen().id();
        UUID strangerTeen = fx().unlinkedTeen().id();
        List<Instant> times = descendingTimestamps(8);
        for (int i = 0; i < 6; i++) {
            insertTask(owner.id(), strangerTeen, "ASSIGNED", times.get(i));
        }
        UUID older1 = insertTask(owner.id(), linkedTeen, "ASSIGNED", times.get(6));
        UUID older2 = insertTask(owner.id(), linkedTeen, "ASSIGNED", times.get(7));

        JsonNode first = page(owner, "limit=2");

        assertThat(idsOf(first)).containsExactly(older1, older2);
        assertThat(first.has("nextCursor")).isFalse();
    }

    @Test
    void aTeenPagesThroughTheirOwnTasks() throws Exception {
        Family family = fx().linkedFamily();
        Owner teen = new Owner(family.teen().bearer(), family.teen().id(), family);
        for (Instant at : descendingTimestamps(5)) {
            insertTask(family.parent().id(), family.teen().id(), "ASSIGNED", at);
        }

        List<UUID> seen =
                walk(teen, null, 2).stream().flatMap(p -> idsOf(p).stream()).toList();

        assertThat(seen)
                .containsExactlyElementsOf(jdbc.queryForList(
                        "SELECT id FROM task.tasks WHERE teen_id = ? ORDER BY created_at DESC, id DESC",
                        UUID.class,
                        teen.id()));
        assertThat(seen).hasSize(5);
    }

    private UUID joinSecondTeen(Owner owner) throws Exception {
        TaskFixtures.Person teen = fx().unlinkedTeen();
        String code = json.readTree(
                        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/me")
                                        .header("Authorization", owner.bearer()))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .at("/joinCode")
                .asText();
        fx().join(new TaskFixtures.Unjoined(
                new Family(new TaskFixtures.Person(owner.id(), owner.bearer()), teen), code));
        return teen.id();
    }
}
