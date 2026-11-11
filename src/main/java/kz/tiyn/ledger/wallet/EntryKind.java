package kz.tiyn.ledger.wallet;

enum EntryKind {
    TASK_REWARD,
    PAYOUT,
    GOAL_LOCK,
    GOAL_UNLOCK,
    PENALTY;

    boolean relatesToTask() {
        return this == TASK_REWARD || this == PENALTY;
    }

    boolean relatesToGoal() {
        return this == GOAL_LOCK || this == GOAL_UNLOCK;
    }
}
