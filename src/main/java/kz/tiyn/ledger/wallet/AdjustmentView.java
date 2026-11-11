package kz.tiyn.ledger.wallet;

import java.time.Instant;
import java.util.UUID;

record AdjustmentView(
        UUID id,
        UUID teenId,
        UUID parentId,
        EntryKind kind,
        long balanceDelta,
        String currency,
        String reason,
        Instant createdAt) {}
