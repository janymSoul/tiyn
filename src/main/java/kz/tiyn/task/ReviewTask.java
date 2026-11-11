package kz.tiyn.task;

import java.time.Instant;
import java.util.UUID;

public record ReviewTask(UUID id, String title, long rewardAmount, String currency, Instant submittedAt) {}
