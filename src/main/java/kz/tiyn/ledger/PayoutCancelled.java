package kz.tiyn.ledger;

import java.util.UUID;

public record PayoutCancelled(UUID payoutId, UUID teenId, UUID parentId, long amount, String currency, String reason) {}
