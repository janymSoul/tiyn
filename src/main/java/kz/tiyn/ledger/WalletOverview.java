package kz.tiyn.ledger;

import java.util.List;

public record WalletOverview(Balance balance, List<GoalSummary> goals, OpenPayout openPayout) {}
