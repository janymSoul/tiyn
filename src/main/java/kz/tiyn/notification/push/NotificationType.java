package kz.tiyn.notification.push;

enum NotificationType {
    TASK_ASSIGNED,
    TASK_SUBMITTED,
    TASK_APPROVED,
    TASK_REJECTED,
    TASK_REDO_REQUESTED,
    TASK_CANCELLED,
    TASK_EXPIRED,
    TASK_EXPIRED_WITH_PENALTY,
    PAYOUT_REQUESTED(true),
    PAYOUT_FULFILLED,
    PAYOUT_CANCELLED_BY_TEEN,
    PAYOUT_DECLINED,
    PAYOUT_EXPIRED,
    PAYOUT_UNLINKED,
    PENALTY_APPLIED,
    FAMILY_LINKED_PARENT,
    FAMILY_LINKED_TEEN,
    FAMILY_UNLINKED;

    private final boolean highPriority;

    NotificationType() {
        this(false);
    }

    NotificationType(boolean highPriority) {
        this.highPriority = highPriority;
    }

    boolean highPriority() {
        return highPriority;
    }

    String code() {
        return name().toLowerCase();
    }
}
