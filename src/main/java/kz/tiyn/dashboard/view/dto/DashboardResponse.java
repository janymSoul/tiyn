package kz.tiyn.dashboard.view.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardResponse(
        BalanceItem balance,
        long pendingAmount,
        List<TaskItem> chores,
        List<TaskItem> milestones,
        List<GoalItem> goals,
        PayoutItem openPayout) {

    public record BalanceItem(long balanceAmount, long lockedAmount, long availableAmount, String currency) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskItem(
            UUID id,
            String kind,
            String title,
            String description,
            long rewardAmount,
            long penaltyAmount,
            String currency,
            String state,
            Instant deadlineAt,
            UUID milestoneId,
            Integer approvedTasks,
            Integer totalTasks) {}

    public record GoalItem(UUID id, String name, long targetAmount, long savedAmount, String currency) {}

    public record PayoutItem(
            UUID id, UUID parentId, long amount, String currency, Instant requestedAt, Instant expiresAt) {}
}
