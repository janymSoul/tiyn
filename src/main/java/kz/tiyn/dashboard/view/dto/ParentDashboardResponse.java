package kz.tiyn.dashboard.view.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ParentDashboardResponse(long totalPendingReviews, long totalOpenPayouts, List<TeenItem> teens) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TeenItem(
            UUID id,
            String displayName,
            BalanceItem balance,
            long pendingReviewCount,
            List<ReviewItem> pendingReviews,
            PayoutItem openPayout) {}

    public record BalanceItem(long balanceAmount, long lockedAmount, long availableAmount, String currency) {}

    public record ReviewItem(UUID id, String title, long rewardAmount, String currency, Instant submittedAt) {}

    public record PayoutItem(UUID id, long amount, String currency, Instant requestedAt) {}
}
