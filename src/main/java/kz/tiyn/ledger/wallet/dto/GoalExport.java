package kz.tiyn.ledger.wallet.dto;

import java.time.Instant;
import java.util.UUID;

public record GoalExport(
        UUID id, String name, long targetAmount, long savedAmount, String currency, String state, Instant createdAt) {}
