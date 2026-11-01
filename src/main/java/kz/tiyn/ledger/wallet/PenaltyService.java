package kz.tiyn.ledger.wallet;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PenaltyService {

    private final WalletStore wallets;
    private final LedgerStore ledger;
    private final TransactionTemplate tx;

    PenaltyService(WalletStore wallets, LedgerStore ledger, TransactionTemplate tx) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.tx = tx;
    }

    void applyMissedTaskPenalty(UUID taskId, UUID teenId, long amount, String currency) {
        if (amount <= 0) {
            return;
        }
        Wallet.requireSupported(currency);
        tx.executeWithoutResult(status -> {
            wallets.ensure(teenId, currency);
            Wallet wallet = wallets.findForUpdate(teenId, currency).orElseThrow();
            if (ledger.exists(LedgerEntry.penaltyKey(taskId))) {
                return;
            }
            ledger.append(wallet.applyPenalty(UUID.randomUUID(), taskId, amount));
            wallets.save(wallet);
        });
    }
}
