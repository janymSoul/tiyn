package kz.tiyn.ledger.wallet.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayoutResponse(
        UUID id,
        UUID teenId,
        UUID parentId,
        long amount,
        String currency,
        String state,
        Instant requestedAt,
        Instant expiresAt,
        Instant resolvedAt,
        String cancelReason) {}
