package kz.tiyn.ledger.wallet.dto;

import java.time.Instant;
import java.util.UUID;

public record AdjustmentResponse(
        UUID id,
        UUID teenId,
        UUID parentId,
        String kind,
        long amount,
        String currency,
        String reason,
        Instant createdAt) {}
