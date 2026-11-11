package kz.tiyn.ledger.wallet.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EntryResponse(
        UUID id,
        String kind,
        long amount,
        String currency,
        Instant createdAt,
        UUID taskId,
        UUID payoutId,
        UUID goalId) {}
