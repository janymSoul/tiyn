package kz.tiyn.ledger;

import java.util.UUID;

public interface BalanceReader {

    Balance balance(UUID teenId);
}
