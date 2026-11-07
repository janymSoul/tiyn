package kz.tiyn.ledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.tiyn.support.PagedEndpointContract;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.Test;

class GoalPagingTests extends PagedEndpointContract {

    @Override
    protected Owner newOwner() throws Exception {
        Family family = new TaskFixtures(mvc, json).linkedFamily();
        return new Owner(family.teen().bearer(), family.teen().id(), family);
    }

    @Override
    protected UUID insertRow(Owner owner, Instant sortKey) {
        jdbc.update(
                "INSERT INTO ledger.wallets (teen_id, currency) VALUES (?, 'KZT') ON CONFLICT DO NOTHING", owner.id());
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO ledger.savings_goals (id, teen_id, currency, name, target_amount, created_at)
                VALUES (?, ?, 'KZT', 'Bike', 1000000, ?)
                """,
                id,
                owner.id(),
                Timestamp.from(sortKey));
        return id;
    }

    @Override
    protected String path() {
        return "/goals";
    }

    @Override
    protected String orderedIdsSql() {
        return "SELECT id FROM ledger.savings_goals WHERE teen_id = ? ORDER BY created_at DESC, id DESC";
    }

    @Test
    void aParentCannotListGoals() throws Exception {
        Family family = new TaskFixtures(mvc, json).linkedFamily();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/goals")
                        .header("Authorization", family.parent().bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void everyGoalOnAPageCarriesItsOwnSavedAmount() throws Exception {
        Owner owner = newOwner();
        List<Instant> times = descendingTimestamps(3);
        UUID newest = insertRow(owner, times.get(0));
        UUID middle = insertRow(owner, times.get(1));
        UUID oldest = insertRow(owner, times.get(2));
        jdbc.update(
                """
                INSERT INTO ledger.ledger_entries
                    (id, teen_id, currency, kind, balance_delta, locked_delta, ref_id, idempotency_key)
                VALUES (?, ?, 'KZT', 'GOAL_LOCK', 0, 70000, ?, ?)
                """,
                UUID.randomUUID(),
                owner.id(),
                middle,
                "saved-" + middle);

        JsonNode firstPage = page(owner, "limit=2");
        JsonNode secondPage =
                page(owner, "limit=2&cursor=" + firstPage.get("nextCursor").asText());

        assertThat(idsOf(firstPage)).containsExactly(newest, middle);
        assertThat(firstPage.get("items").get(0).get("savedAmount").asLong()).isZero();
        assertThat(firstPage.get("items").get(1).get("savedAmount").asLong()).isEqualTo(70000);
        assertThat(idsOf(secondPage)).containsExactly(oldest);
        assertThat(secondPage.get("items").get(0).get("savedAmount").asLong()).isZero();
    }
}
