package kz.tiyn.ledger.wallet;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import kz.tiyn.support.PagedEndpointContract;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;

class WalletEntriesPagingTests extends PagedEndpointContract {

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
                INSERT INTO ledger.ledger_entries
                    (id, teen_id, currency, kind, balance_delta, locked_delta, ref_id, idempotency_key, created_at)
                VALUES (?, ?, 'KZT', 'TASK_REWARD', 1000, 0, ?, ?, ?)
                """,
                id,
                owner.id(),
                UUID.randomUUID(),
                "history-" + id,
                Timestamp.from(sortKey));
        return id;
    }

    @Override
    protected String path() {
        return "/wallet/entries";
    }

    @Override
    protected String orderedIdsSql() {
        return "SELECT id FROM ledger.ledger_entries WHERE teen_id = ? ORDER BY created_at DESC, id DESC";
    }
}
