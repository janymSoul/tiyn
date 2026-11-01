package kz.tiyn.task;

import java.util.UUID;

public record TaskAssigned(UUID taskId, UUID teenId, String kind, long rewardAmount, String currency) {}
