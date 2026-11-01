package kz.tiyn.ledger.wallet.dto;

import java.util.UUID;

public record GoalResponse(UUID id, String name, long targetAmount, long savedAmount, String currency, String state) {}
