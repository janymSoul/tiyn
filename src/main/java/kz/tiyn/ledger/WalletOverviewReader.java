package kz.tiyn.ledger;

import java.util.UUID;

public interface WalletOverviewReader {

    WalletOverview overview(UUID teenId);
}
