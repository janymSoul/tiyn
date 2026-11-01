package kz.tiyn.ledger.wallet;

import java.util.List;
import java.util.UUID;
import kz.tiyn.ledger.GoalSummary;
import kz.tiyn.ledger.OpenPayout;
import kz.tiyn.ledger.WalletOverview;
import kz.tiyn.ledger.WalletOverviewReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WalletOverviewReaderImpl implements WalletOverviewReader {

    private final BalanceReaderImpl balances;
    private final GoalStore goals;
    private final PayoutStore payouts;

    WalletOverviewReaderImpl(BalanceReaderImpl balances, GoalStore goals, PayoutStore payouts) {
        this.balances = balances;
        this.goals = goals;
        this.payouts = payouts;
    }

    @Override
    @Transactional(readOnly = true)
    public WalletOverview overview(UUID teenId) {
        List<GoalSummary> active = goals.listForTeen(teenId).stream()
                .filter(v -> v.goal().state() == GoalState.ACTIVE)
                .map(v -> new GoalSummary(
                        v.goal().id(),
                        v.goal().name(),
                        v.goal().targetAmount(),
                        v.savedAmount(),
                        v.goal().currency()))
                .toList();
        OpenPayout open = payouts.listForTeen(teenId, PayoutState.REQUESTED).stream()
                .findFirst()
                .map(p -> new OpenPayout(p.id(), p.parentId(), p.amount(), p.currency(), p.requestedAt()))
                .orElse(null);
        return new WalletOverview(balances.balance(teenId), active, open);
    }
}
