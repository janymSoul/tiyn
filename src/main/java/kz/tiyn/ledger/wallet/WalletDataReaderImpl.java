package kz.tiyn.ledger.wallet;

import static kz.tiyn.platform.jooq.ledger.Tables.LEDGER_ENTRIES;
import static kz.tiyn.platform.jooq.ledger.Tables.PAYOUTS;
import static kz.tiyn.platform.jooq.ledger.Tables.SAVINGS_GOALS;
import static kz.tiyn.platform.jooq.ledger.Tables.WALLETS;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kz.tiyn.ledger.WalletDataReader;
import kz.tiyn.ledger.wallet.dto.GoalExport;
import kz.tiyn.ledger.wallet.dto.LedgerEntryExport;
import kz.tiyn.ledger.wallet.dto.PayoutExport;
import kz.tiyn.ledger.wallet.dto.WalletExport;
import kz.tiyn.platform.export.ExportProperties;
import kz.tiyn.platform.export.ExportWriter;
import kz.tiyn.platform.export.KeysetScan;
import kz.tiyn.platform.export.KeysetScan.Position;
import kz.tiyn.platform.jooq.ledger.tables.records.LedgerEntriesRecord;
import kz.tiyn.platform.jooq.ledger.tables.records.PayoutsRecord;
import kz.tiyn.platform.security.Role;
import kz.tiyn.platform.time.Instants;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class WalletDataReaderImpl implements WalletDataReader {

    private final DSLContext dsl;
    private final KeysetScan scan;

    WalletDataReaderImpl(DSLContext dsl, ExportProperties properties) {
        this.dsl = dsl;
        this.scan = new KeysetScan(properties.pageSize());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void writeTo(UUID userId, Role role, ExportWriter out) {
        if (role == Role.TEEN) {
            out.field("wallet", wallet(userId));
            out.array("ledgerEntries", items -> scanEntries(userId, items));
            out.array("savingsGoals", items -> scanGoals(userId, items));
            out.array("payouts", items -> scanPayouts(PAYOUTS.TEEN_ID.eq(userId), items));
            return;
        }
        out.array("payouts", items -> scanPayouts(PAYOUTS.PARENT_ID.eq(userId), items));
    }

    private WalletExport wallet(UUID teenId) {
        return dsl.select(WALLETS.BALANCE_AMOUNT, WALLETS.LOCKED_AMOUNT, WALLETS.CURRENCY)
                .from(WALLETS)
                .where(WALLETS.TEEN_ID.eq(teenId))
                .fetchOptional(r -> new WalletExport(
                        r.get(WALLETS.BALANCE_AMOUNT), r.get(WALLETS.LOCKED_AMOUNT), r.get(WALLETS.CURRENCY)))
                .orElse(new WalletExport(0, 0, Wallet.KZT));
    }

    private void scanEntries(UUID teenId, ExportWriter.Items items) {
        scan.scan(
                (after, limit) -> entriesAfter(teenId, after, limit),
                entry -> new Position(entry.createdAt(), entry.id()),
                page -> page.forEach(items::add));
    }

    private List<LedgerEntryExport> entriesAfter(UUID teenId, Position after, int limit) {
        var ordered = dsl.selectFrom(LEDGER_ENTRIES)
                .where(LEDGER_ENTRIES.TEEN_ID.eq(teenId))
                .orderBy(LEDGER_ENTRIES.CREATED_AT.desc(), LEDGER_ENTRIES.ID.desc());
        List<LedgerEntriesRecord> fetched = after == null
                ? ordered.limit(limit).fetch()
                : ordered.seek(Instants.toUtc(after.sortKey()), after.id())
                        .limit(limit)
                        .fetch();
        return fetched.stream().map(WalletDataReaderImpl::toExport).toList();
    }

    private static LedgerEntryExport toExport(LedgerEntriesRecord e) {
        return new LedgerEntryExport(
                e.getId(),
                e.getKind(),
                e.getBalanceDelta(),
                e.getLockedDelta(),
                e.getCurrency(),
                e.getRefId(),
                e.getNote(),
                e.getActorId(),
                Instants.fromUtc(e.getCreatedAt()));
    }

    private void scanGoals(UUID teenId, ExportWriter.Items items) {
        scan.scan(
                (after, limit) -> goalsAfter(teenId, after, limit),
                goal -> new Position(goal.createdAt(), goal.id()),
                page -> page.forEach(items::add));
    }

    private List<GoalExport> goalsAfter(UUID teenId, Position after, int limit) {
        Field<BigDecimal> saved = GoalStore.savedAmount();
        var ordered = dsl.select(SAVINGS_GOALS.asterisk(), saved)
                .from(SAVINGS_GOALS)
                .where(SAVINGS_GOALS.TEEN_ID.eq(teenId))
                .orderBy(SAVINGS_GOALS.CREATED_AT.desc(), SAVINGS_GOALS.ID.desc());
        List<Record> fetched = after == null
                ? ordered.limit(limit).fetch()
                : ordered.seek(Instants.toUtc(after.sortKey()), after.id())
                        .limit(limit)
                        .fetch();
        return fetched.stream()
                .map(r -> new GoalExport(
                        r.get(SAVINGS_GOALS.ID),
                        r.get(SAVINGS_GOALS.NAME),
                        r.get(SAVINGS_GOALS.TARGET_AMOUNT),
                        r.get(saved).longValueExact(),
                        r.get(SAVINGS_GOALS.CURRENCY),
                        r.get(SAVINGS_GOALS.STATE),
                        Instants.fromUtc(r.get(SAVINGS_GOALS.CREATED_AT))))
                .toList();
    }

    private void scanPayouts(Condition owner, ExportWriter.Items items) {
        scan.scan(
                (after, limit) -> payoutsAfter(owner, after, limit),
                payout -> new Position(payout.requestedAt(), payout.id()),
                page -> page.forEach(items::add));
    }

    private List<PayoutExport> payoutsAfter(Condition owner, Position after, int limit) {
        var ordered = dsl.selectFrom(PAYOUTS).where(owner).orderBy(PAYOUTS.REQUESTED_AT.desc(), PAYOUTS.ID.desc());
        List<PayoutsRecord> fetched = after == null
                ? ordered.limit(limit).fetch()
                : ordered.seek(Instants.toUtc(after.sortKey()), after.id())
                        .limit(limit)
                        .fetch();
        return fetched.stream()
                .map(p -> new PayoutExport(
                        p.getId(),
                        p.getTeenId(),
                        p.getParentId(),
                        p.getAmount(),
                        p.getCurrency(),
                        p.getState(),
                        Instants.fromUtc(p.getRequestedAt()),
                        Instants.fromUtc(p.getResolvedAt()),
                        p.getResolvedBy(),
                        p.getCancelReason()))
                .toList();
    }
}
