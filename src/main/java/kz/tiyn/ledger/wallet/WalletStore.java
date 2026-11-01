package kz.tiyn.ledger.wallet;

import static kz.tiyn.platform.jooq.ledger.Tables.PAYOUTS;
import static kz.tiyn.platform.jooq.ledger.Tables.WALLETS;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record5;
import org.jooq.SelectConditionStep;
import org.jooq.impl.DSL;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Repository;

@Repository
class WalletStore {

    private final DSLContext dsl;
    private final ApplicationEventPublisher events;

    WalletStore(DSLContext dsl, ApplicationEventPublisher events) {
        this.dsl = dsl;
        this.events = events;
    }

    void ensure(UUID teenId, String currency) {
        dsl.insertInto(WALLETS)
                .set(WALLETS.TEEN_ID, teenId)
                .set(WALLETS.CURRENCY, currency)
                .onConflictDoNothing()
                .execute();
    }

    Optional<Wallet> find(UUID teenId, String currency) {
        return select(teenId, currency).fetchOptional(WalletStore::toWallet);
    }

    Optional<Wallet> findForUpdate(UUID teenId, String currency) {
        dsl.select(WALLETS.TEEN_ID)
                .from(WALLETS)
                .where(WALLETS.TEEN_ID.eq(teenId).and(WALLETS.CURRENCY.eq(currency)))
                .forUpdate()
                .fetch();
        return find(teenId, currency);
    }

    void save(Wallet wallet) {
        dsl.update(WALLETS)
                .set(WALLETS.BALANCE_AMOUNT, wallet.balanceAmount())
                .set(WALLETS.LOCKED_AMOUNT, wallet.lockedAmount())
                .where(WALLETS.TEEN_ID.eq(wallet.teenId()).and(WALLETS.CURRENCY.eq(wallet.currency())))
                .execute();
        wallet.pullEvents().forEach(events::publishEvent);
    }

    private SelectConditionStep<Record5<UUID, String, Long, Long, BigDecimal>> select(UUID teenId, String currency) {
        Field<BigDecimal> openRequested = DSL.field(dsl.select(DSL.coalesce(DSL.sum(PAYOUTS.AMOUNT), BigDecimal.ZERO))
                .from(PAYOUTS)
                .where(PAYOUTS.TEEN_ID.eq(WALLETS.TEEN_ID))
                .and(PAYOUTS.CURRENCY.eq(WALLETS.CURRENCY))
                .and(PAYOUTS.STATE.eq(PayoutState.REQUESTED.name())));
        return dsl.select(
                        WALLETS.TEEN_ID, WALLETS.CURRENCY, WALLETS.BALANCE_AMOUNT, WALLETS.LOCKED_AMOUNT, openRequested)
                .from(WALLETS)
                .where(WALLETS.TEEN_ID.eq(teenId).and(WALLETS.CURRENCY.eq(currency)));
    }

    private static Wallet toWallet(Record5<UUID, String, Long, Long, BigDecimal> r) {
        return Wallet.restore(
                r.value1(), r.value2(), r.value3(), r.value4(), r.value5().longValueExact());
    }
}
