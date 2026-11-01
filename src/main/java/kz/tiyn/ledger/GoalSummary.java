package kz.tiyn.ledger;

import java.util.UUID;

public record GoalSummary(UUID id, String name, long targetAmount, long savedAmount, String currency) {}
