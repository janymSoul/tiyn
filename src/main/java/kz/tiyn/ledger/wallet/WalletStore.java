package kz.tiyn.ledger.wallet;

import static kz.tiyn.platform.jooq.ledger.Tables.WALLETS;

import java.util.Optional;
import java.util.UUID;
import kz.tiyn.platform.jooq.ledger.tables.records.WalletsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
class WalletStore {

    private final DSLContext dsl;
    private final PayoutStore payouts;

    WalletStore(DSLContext dsl, PayoutStore payouts) {
        this.dsl = dsl;
        this.payouts = payouts;
    }

    void ensure(UUID teenId, String currency) {
        dsl.insertInto(WALLETS)
                .set(WALLETS.TEEN_ID, teenId)
                .set(WALLETS.CURRENCY, currency)
                .onConflictDoNothing()
                .execute();
    }

    Optional<Wallet> find(UUID teenId, String currency) {
        return dsl.selectFrom(WALLETS)
                .where(WALLETS.TEEN_ID.eq(teenId).and(WALLETS.CURRENCY.eq(currency)))
                .fetchOptional()
                .map(this::toWallet);
    }

    Optional<Wallet> findForUpdate(UUID teenId, String currency) {
        return dsl.selectFrom(WALLETS)
                .where(WALLETS.TEEN_ID.eq(teenId).and(WALLETS.CURRENCY.eq(currency)))
                .forUpdate()
                .fetchOptional()
                .map(this::toWallet);
    }

    void save(Wallet wallet) {
        dsl.update(WALLETS)
                .set(WALLETS.BALANCE_AMOUNT, wallet.balanceAmount())
                .set(WALLETS.LOCKED_AMOUNT, wallet.lockedAmount())
                .where(WALLETS.TEEN_ID.eq(wallet.teenId()).and(WALLETS.CURRENCY.eq(wallet.currency())))
                .execute();
    }

    private Wallet toWallet(WalletsRecord r) {
        return Wallet.restore(
                r.getTeenId(),
                r.getCurrency(),
                r.getBalanceAmount(),
                r.getLockedAmount(),
                payouts.openAmount(r.getTeenId(), r.getCurrency()));
    }
}
