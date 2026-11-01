package kz.tiyn.notification.push;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.tiyn.identity.FamilyLinked;
import kz.tiyn.identity.FamilyUnlinked;
import kz.tiyn.ledger.PayoutCancelled;
import kz.tiyn.ledger.PayoutFulfilled;
import kz.tiyn.ledger.PayoutRequested;
import kz.tiyn.ledger.PenaltyApplied;
import kz.tiyn.task.TaskApproved;
import kz.tiyn.task.TaskAssigned;
import kz.tiyn.task.TaskCancelled;
import kz.tiyn.task.TaskExpired;
import kz.tiyn.task.TaskRedoRequested;
import kz.tiyn.task.TaskRejected;
import kz.tiyn.task.TaskSubmitted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class NotificationListener {

    private static final String TEEN_CANCELLED = "TEEN_CANCELLED";

    private final NotificationService notifications;

    NotificationListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    void on(TaskAssigned e) {
        notify(
                "TaskAssigned:" + e.taskId(),
                e.teenId(),
                NotificationType.TASK_ASSIGNED,
                taskData(e.taskId()),
                money(e.rewardAmount()));
    }

    @ApplicationModuleListener
    void on(TaskSubmitted e) {
        notify(
                "TaskSubmitted:" + e.taskId() + ":" + e.attemptNo(),
                e.parentId(),
                NotificationType.TASK_SUBMITTED,
                taskData(e.taskId()));
    }

    @ApplicationModuleListener
    void on(TaskApproved e) {
        notify(
                "TaskApproved:" + e.taskId(),
                e.teenId(),
                NotificationType.TASK_APPROVED,
                taskData(e.taskId()),
                money(e.rewardAmount()));
    }

    @ApplicationModuleListener
    void on(TaskRejected e) {
        notify(
                "TaskRejected:" + e.taskId(),
                e.teenId(),
                NotificationType.TASK_REJECTED,
                taskData(e.taskId()),
                Messages.shorten(e.note()));
    }

    @ApplicationModuleListener
    void on(TaskRedoRequested e) {
        notify(
                "TaskRedoRequested:" + e.taskId() + ":" + e.attemptNo(),
                e.teenId(),
                NotificationType.TASK_REDO_REQUESTED,
                taskData(e.taskId()),
                Messages.shorten(e.note()));
    }

    @ApplicationModuleListener
    void on(TaskCancelled e) {
        notify("TaskCancelled:" + e.taskId(), e.teenId(), NotificationType.TASK_CANCELLED, taskData(e.taskId()));
    }

    @ApplicationModuleListener
    void on(TaskExpired e) {
        if (e.penaltyAmount() > 0) {
            notify(
                    "TaskExpired:" + e.taskId(),
                    e.teenId(),
                    NotificationType.TASK_EXPIRED_WITH_PENALTY,
                    taskData(e.taskId()),
                    money(e.penaltyAmount()));
        } else {
            notify("TaskExpired:" + e.taskId(), e.teenId(), NotificationType.TASK_EXPIRED, taskData(e.taskId()));
        }
    }

    @ApplicationModuleListener
    void on(PayoutRequested e) {
        notify(
                "PayoutRequested:" + e.payoutId(),
                e.parentId(),
                NotificationType.PAYOUT_REQUESTED,
                payoutData(e.payoutId()),
                money(e.amount()));
    }

    @ApplicationModuleListener
    void on(PayoutFulfilled e) {
        notify(
                "PayoutFulfilled:" + e.payoutId(),
                e.teenId(),
                NotificationType.PAYOUT_FULFILLED,
                payoutData(e.payoutId()),
                money(e.amount()));
    }

    @ApplicationModuleListener
    void on(PayoutCancelled e) {
        boolean byTeen = TEEN_CANCELLED.equals(e.reason());
        NotificationType type =
                switch (e.reason()) {
                    case TEEN_CANCELLED -> NotificationType.PAYOUT_CANCELLED_BY_TEEN;
                    case "DECLINED" -> NotificationType.PAYOUT_DECLINED;
                    case "EXPIRED" -> NotificationType.PAYOUT_EXPIRED;
                    default -> NotificationType.PAYOUT_UNLINKED;
                };
        notify("PayoutCancelled:" + e.payoutId(), byTeen ? e.parentId() : e.teenId(), type, payoutData(e.payoutId()));
    }

    @ApplicationModuleListener
    void on(PenaltyApplied e) {
        notify(
                "PenaltyApplied:" + e.entryId(),
                e.teenId(),
                NotificationType.PENALTY_APPLIED,
                taskData(e.taskId()),
                money(e.amount()));
    }

    @ApplicationModuleListener
    void on(FamilyLinked e) {
        String key = "FamilyLinked:" + e.parentId() + ":" + e.teenId() + ":" + e.generation();
        notify(key, e.parentId(), NotificationType.FAMILY_LINKED_PARENT, Map.of());
        notify(key, e.teenId(), NotificationType.FAMILY_LINKED_TEEN, Map.of());
    }

    @ApplicationModuleListener
    void on(FamilyUnlinked e) {
        notify(
                "FamilyUnlinked:" + e.parentId() + ":" + e.teenId() + ":" + e.generation(),
                e.teenId(),
                NotificationType.FAMILY_UNLINKED,
                Map.of());
    }

    private void notify(String key, UUID recipient, NotificationType type, Map<String, String> data, String... args) {
        Map<String, String> payload = new java.util.HashMap<>(data);
        payload.put("type", type.name());
        notifications.notify(key, recipient, type, List.of(args), Map.copyOf(payload));
    }

    private static Map<String, String> taskData(UUID taskId) {
        return Map.of("taskId", taskId.toString());
    }

    private static Map<String, String> payoutData(UUID payoutId) {
        return Map.of("payoutId", payoutId.toString());
    }

    private static String money(long minorUnits) {
        return MoneyFormat.tenge(minorUnits);
    }
}
