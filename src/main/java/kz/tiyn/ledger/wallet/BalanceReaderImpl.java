package kz.tiyn.ledger.wallet;

import java.util.UUID;
import kz.tiyn.ledger.Balance;
import kz.tiyn.ledger.BalanceReader;
import org.springframework.stereotype.Service;

@Service
class BalanceReaderImpl implements BalanceReader {

    private final WalletStore wallets;

    BalanceReaderImpl(WalletStore wallets) {
        this.wallets = wallets;
    }

    @Override
    public Balance balance(UUID teenId) {
        return wallets.find(teenId, Wallet.KZT)
                .map(w -> new Balance(w.balanceAmount(), w.lockedAmount(), w.availableAmount(), w.currency()))
                .orElse(new Balance(0, 0, 0, Wallet.KZT));
    }
}
