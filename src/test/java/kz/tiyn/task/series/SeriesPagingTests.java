package kz.tiyn.task.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.tiyn.support.PagedEndpointContract;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.Test;

class SeriesPagingTests extends PagedEndpointContract {

    @Override
    protected Owner newOwner() throws Exception {
        Family family = new TaskFixtures(mvc, json).linkedFamily();
        return new Owner(family.parent().bearer(), family.parent().id(), family);
    }

    @Override
    protected UUID insertRow(Owner owner, Instant sortKey) {
        return insertSeries(owner.id(), ((Family) owner.context()).teen().id(), true, sortKey);
    }

    private UUID insertSeries(UUID parentId, UUID teenId, boolean active, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO task.task_series
                    (id, parent_id, teen_id, kind, title, reward_amount, currency, recurrence, timezone,
                     due_time, starts_on, active, created_at)
                VALUES (?, ?, ?, 'TASK', 'Paged', 100, 'KZT', 'DAILY', 'Asia/Almaty', '23:59', current_date, ?, ?)
                """,
                id,
                parentId,
                teenId,
                active,
                Timestamp.from(createdAt));
        return id;
    }

    @Override
    protected String path() {
        return "/task-series";
    }

    @Override
    protected String orderedIdsSql() {
        return "SELECT id FROM task.task_series WHERE parent_id = ? ORDER BY created_at DESC, id DESC";
    }

    @Override
    protected String otherFilter() {
        return "active=false";
    }

    @Test
    void theActiveFilterAppliesAcrossPages() throws Exception {
        Owner owner = newOwner();
        UUID teen = ((Family) owner.context()).teen().id();
        List<Instant> times = descendingTimestamps(6);
        for (int i = 0; i < 6; i++) {
            insertSeries(owner.id(), teen, i % 2 == 0, times.get(i));
        }

        List<UUID> stopped = walk(owner, "active=false", 2).stream()
                .flatMap(p -> idsOf(p).stream())
                .toList();

        assertThat(stopped).hasSize(3);
        assertThat(stopped)
                .containsExactlyElementsOf(jdbc.queryForList(
                        "SELECT id FROM task.task_series WHERE parent_id = ? AND NOT active"
                                + " ORDER BY created_at DESC, id DESC",
                        UUID.class,
                        owner.id()));
    }

    @Test
    void theTeenFilterAppliesAcrossPagesAndAnUnlinkedTeenIsForbidden() throws Exception {
        Owner owner = newOwner();
        Family family = (Family) owner.context();
        UUID stranger = new TaskFixtures(mvc, json).unlinkedTeen().id();
        for (Instant at : descendingTimestamps(5)) {
            insertSeries(owner.id(), family.teen().id(), true, at);
        }

        List<UUID> mine = walk(owner, "teenId=" + family.teen().id(), 2).stream()
                .flatMap(p -> idsOf(p).stream())
                .toList();

        assertThat(mine).hasSize(5).containsExactlyElementsOf(expectedOrder(owner));
        request(owner, "teenId=" + stranger).andExpect(status().isForbidden());
    }
}
