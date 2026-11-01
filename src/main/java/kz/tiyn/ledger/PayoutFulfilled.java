package kz.tiyn.ledger;

import java.util.UUID;

public record PayoutFulfilled(UUID payoutId, UUID teenId, UUID parentId, long amount, String currency) {}
