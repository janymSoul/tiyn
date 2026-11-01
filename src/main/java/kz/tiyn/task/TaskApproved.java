package kz.tiyn.task;

import java.util.UUID;

public record TaskApproved(UUID taskId, UUID teenId, long rewardAmount, String currency) {}
