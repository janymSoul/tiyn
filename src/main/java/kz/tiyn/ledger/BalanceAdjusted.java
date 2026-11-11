package kz.tiyn.ledger;

import java.util.UUID;

public record BalanceAdjusted(
        UUID entryId, UUID teenId, UUID parentId, AdjustmentKind kind, long amount, String currency) {}
