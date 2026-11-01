package kz.tiyn.ledger;

import java.time.Instant;
import java.util.UUID;

public record OpenPayout(UUID id, UUID parentId, long amount, String currency, Instant requestedAt) {}
