package kz.tiyn.ledger.wallet;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class WalletService {

    private final WalletStore wallets;
    private final LedgerStore ledger;
    private final TransactionTemplate tx;

    WalletService(WalletStore wallets, LedgerStore ledger, TransactionTemplate tx) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.tx = tx;
    }

    void creditTaskReward(UUID taskId, UUID teenId, long amount, String currency) {
        Wallet.requireSupported(currency);
        tx.executeWithoutResult(status -> {
            wallets.ensure(teenId, currency);
            Wallet wallet = wallets.findForUpdate(teenId, currency).orElseThrow();
            if (ledger.exists(LedgerEntry.rewardKey(taskId))) {
                return;
            }
            LedgerEntry entry = wallet.creditTaskReward(UUID.randomUUID(), taskId, amount);
            ledger.append(entry);
            wallets.save(wallet);
        });
    }
}
