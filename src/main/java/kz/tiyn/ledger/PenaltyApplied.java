package kz.tiyn.ledger;

import java.util.UUID;

public record PenaltyApplied(UUID entryId, UUID teenId, UUID taskId, long amount, String currency) {}
