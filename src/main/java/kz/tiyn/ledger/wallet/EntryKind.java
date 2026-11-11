package kz.tiyn.ledger.wallet;

enum EntryKind {
    TASK_REWARD,
    PAYOUT,
    GOAL_LOCK,
    GOAL_UNLOCK,
    PENALTY,
    MANUAL_BONUS,
    MANUAL_PENALTY;

    boolean relatesToTask() {
        return this == TASK_REWARD || this == PENALTY;
    }

    boolean isManual() {
        return this == MANUAL_BONUS || this == MANUAL_PENALTY;
    }

    boolean relatesToGoal() {
        return this == GOAL_LOCK || this == GOAL_UNLOCK;
    }
}
