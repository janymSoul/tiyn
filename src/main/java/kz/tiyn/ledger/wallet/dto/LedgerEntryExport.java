package kz.tiyn.ledger.wallet.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LedgerEntryExport(
        UUID id,
        String kind,
        long balanceDelta,
        long lockedDelta,
        String currency,
        UUID refId,
        String note,
        UUID actorId,
        Instant createdAt) {}
