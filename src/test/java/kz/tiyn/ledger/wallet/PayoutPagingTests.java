package kz.tiyn.ledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.tiyn.support.PagedEndpointContract;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.Test;

class PayoutPagingTests extends PagedEndpointContract {

    private TaskFixtures fx() {
        return new TaskFixtures(mvc, json);
    }

    @Override
    protected Owner newOwner() throws Exception {
        Family family = fx().linkedFamily();
        return new Owner(family.teen().bearer(), family.teen().id(), family);
    }

    @Override
    protected UUID insertRow(Owner owner, Instant sortKey) {
        Family family = (Family) owner.context();
        return insertPayout(family, "CANCELLED", sortKey);
    }

    private UUID insertPayout(Family family, String state, Instant requestedAt) {
        jdbc.update(
                "INSERT INTO ledger.wallets (teen_id, currency) VALUES (?, 'KZT') ON CONFLICT DO NOTHING",
                family.teen().id());
        UUID id = UUID.randomUUID();
        boolean fulfilled = "FULFILLED".equals(state);
        jdbc.update(
                """
                INSERT INTO ledger.payouts
                    (id, teen_id, currency, parent_id, amount, state, idempotency_key, requested_at,
                     resolved_at, resolved_by, cancel_reason)
                VALUES (?, ?, 'KZT', ?, 10000, ?, ?, ?, now(), ?, ?)
                """,
                id,
                family.teen().id(),
                family.parent().id(),
                state,
                "paging-" + id,
                Timestamp.from(requestedAt),
                fulfilled ? family.parent().id() : null,
                fulfilled ? null : "DECLINED");
        return id;
    }

    @Override
    protected String path() {
        return "/payouts";
    }

    @Override
    protected String orderedIdsSql() {
        return "SELECT id FROM ledger.payouts WHERE teen_id = ? ORDER BY requested_at DESC, id DESC";
    }

    @Override
    protected String otherFilter() {
        return "state=FULFILLED";
    }

    @Test
    void theStateFilterAppliesAcrossPages() throws Exception {
        Owner owner = newOwner();
        Family family = (Family) owner.context();
        List<Instant> times = descendingTimestamps(6);
        for (int i = 0; i < 6; i++) {
            insertPayout(family, i % 2 == 0 ? "FULFILLED" : "CANCELLED", times.get(i));
        }

        List<UUID> fulfilled = walk(owner, "state=fulfilled", 2).stream()
                .flatMap(p -> idsOf(p).stream())
                .toList();

        assertThat(fulfilled)
                .containsExactlyElementsOf(jdbc.queryForList(
                        "SELECT id FROM ledger.payouts WHERE teen_id = ? AND state = 'FULFILLED'"
                                + " ORDER BY requested_at DESC, id DESC",
                        UUID.class,
                        owner.id()));
        assertThat(fulfilled).hasSize(3);
    }

    @Test
    void theAddressedParentPagesThroughTheirOwnPayoutsOnly() throws Exception {
        Family family = fx().linkedFamily();
        Family other = fx().linkedFamily();
        for (Instant at : descendingTimestamps(5)) {
            insertPayout(family, "CANCELLED", at);
            insertPayout(other, "CANCELLED", at);
        }
        Owner parent = new Owner(family.parent().bearer(), family.parent().id(), family);

        List<UUID> seen =
                walk(parent, null, 2).stream().flatMap(p -> idsOf(p).stream()).toList();

        assertThat(seen)
                .containsExactlyElementsOf(jdbc.queryForList(
                        "SELECT id FROM ledger.payouts WHERE parent_id = ? ORDER BY requested_at DESC, id DESC",
                        UUID.class,
                        parent.id()));
        assertThat(seen).hasSize(5);
    }
}
