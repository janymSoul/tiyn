package kz.tiyn.dashboard.view;

import java.util.List;
import java.util.UUID;
import kz.tiyn.dashboard.view.dto.ParentDashboardResponse;
import kz.tiyn.dashboard.view.dto.ParentDashboardResponse.BalanceItem;
import kz.tiyn.dashboard.view.dto.ParentDashboardResponse.PayoutItem;
import kz.tiyn.dashboard.view.dto.ParentDashboardResponse.ReviewItem;
import kz.tiyn.dashboard.view.dto.ParentDashboardResponse.TeenItem;
import kz.tiyn.identity.FamilyDirectory;
import kz.tiyn.identity.LinkedTeen;
import kz.tiyn.ledger.Balance;
import kz.tiyn.ledger.OpenPayout;
import kz.tiyn.ledger.WalletOverview;
import kz.tiyn.ledger.WalletOverviewReader;
import kz.tiyn.task.ReviewQueue;
import kz.tiyn.task.ReviewQueueReader;
import org.springframework.stereotype.Service;

@Service
class ParentDashboardService {

    private final FamilyDirectory family;
    private final WalletOverviewReader wallets;
    private final ReviewQueueReader reviews;
    private final DashboardProperties properties;

    ParentDashboardService(
            FamilyDirectory family,
            WalletOverviewReader wallets,
            ReviewQueueReader reviews,
            DashboardProperties properties) {
        this.family = family;
        this.wallets = wallets;
        this.reviews = reviews;
        this.properties = properties;
    }

    ParentDashboardResponse forParent(UUID parentId) {
        List<TeenItem> teens = family.linkedTeens(parentId).stream()
                .map(teen -> item(parentId, teen))
                .toList();
        return new ParentDashboardResponse(
                teens.stream().mapToLong(TeenItem::pendingReviewCount).sum(),
                teens.stream().filter(t -> t.openPayout() != null).count(),
                teens);
    }

    private TeenItem item(UUID parentId, LinkedTeen teen) {
        WalletOverview overview = wallets.overview(teen.id());
        ReviewQueue queue = reviews.reviewQueue(parentId, teen.id(), properties.reviewPreview());
        Balance balance = overview.balance();
        return new TeenItem(
                teen.id(),
                teen.displayName(),
                new BalanceItem(
                        balance.balanceAmount(), balance.lockedAmount(), balance.availableAmount(), balance.currency()),
                queue.total(),
                queue.tasks().stream()
                        .map(t -> new ReviewItem(t.id(), t.title(), t.rewardAmount(), t.currency(), t.submittedAt()))
                        .toList(),
                payoutTo(parentId, overview.openPayout()));
    }

    private static PayoutItem payoutTo(UUID parentId, OpenPayout payout) {
        if (payout == null || !payout.parentId().equals(parentId)) {
            return null;
        }
        return new PayoutItem(payout.id(), payout.amount(), payout.currency(), payout.requestedAt());
    }
}
