package kz.tiyn.ledger;

import java.util.UUID;

public record PayoutRequested(UUID payoutId, UUID teenId, UUID parentId, long amount, String currency) {}
