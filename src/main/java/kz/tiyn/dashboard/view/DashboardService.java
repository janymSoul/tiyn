package kz.tiyn.dashboard.view;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import kz.tiyn.dashboard.view.dto.DashboardResponse;
import kz.tiyn.dashboard.view.dto.DashboardResponse.BalanceItem;
import kz.tiyn.dashboard.view.dto.DashboardResponse.GoalItem;
import kz.tiyn.dashboard.view.dto.DashboardResponse.PayoutItem;
import kz.tiyn.dashboard.view.dto.DashboardResponse.TaskItem;
import kz.tiyn.ledger.WalletOverview;
import kz.tiyn.ledger.WalletOverviewReader;
import kz.tiyn.task.BoardTask;
import kz.tiyn.task.PendingReader;
import kz.tiyn.task.TaskBoardReader;
import org.springframework.stereotype.Service;

@Service
class DashboardService {

    private static final String MILESTONE = "MILESTONE";

    private final WalletOverviewReader wallets;
    private final TaskBoardReader board;
    private final PendingReader pending;
    private final DashboardProperties properties;
    private final Clock clock;

    DashboardService(
            WalletOverviewReader wallets,
            TaskBoardReader board,
            PendingReader pending,
            DashboardProperties properties,
            Clock clock) {
        this.wallets = wallets;
        this.board = board;
        this.pending = pending;
        this.properties = properties;
        this.clock = clock;
    }

    DashboardResponse forTeen(UUID teenId) {
        WalletOverview overview = wallets.overview(teenId);
        List<BoardTask> tasks =
                board.board(teenId, clock.instant().minus(properties.recentWindow()), properties.maxTasks());
        var balance = overview.balance();
        return new DashboardResponse(
                new BalanceItem(
                        balance.balanceAmount(), balance.lockedAmount(), balance.availableAmount(), balance.currency()),
                pending.pendingReward(teenId),
                tasks.stream()
                        .filter(t -> !MILESTONE.equals(t.kind()))
                        .map(DashboardService::item)
                        .toList(),
                tasks.stream()
                        .filter(t -> MILESTONE.equals(t.kind()))
                        .map(DashboardService::item)
                        .toList(),
                overview.goals().stream()
                        .map(g -> new GoalItem(g.id(), g.name(), g.targetAmount(), g.savedAmount(), g.currency()))
                        .toList(),
                overview.openPayout() == null
                        ? null
                        : new PayoutItem(
                                overview.openPayout().id(),
                                overview.openPayout().parentId(),
                                overview.openPayout().amount(),
                                overview.openPayout().currency(),
                                overview.openPayout().requestedAt(),
                                overview.openPayout().expiresAt()));
    }

    private static TaskItem item(BoardTask t) {
        return new TaskItem(
                t.id(),
                t.kind(),
                t.title(),
                t.description(),
                t.rewardAmount(),
                t.penaltyAmount(),
                t.currency(),
                t.state(),
                t.deadlineAt(),
                t.milestoneId(),
                t.approvedTasks(),
                t.totalTasks());
    }
}
