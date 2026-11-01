package kz.tiyn.ledger.wallet;

import java.util.UUID;

record LedgerEntry(
        UUID id,
        UUID teenId,
        String currency,
        EntryKind kind,
        long balanceDelta,
        long lockedDelta,
        UUID refId,
        String idempotencyKey) {

    static String rewardKey(UUID taskId) {
        return "REWARD-TASK-" + taskId;
    }

    static String goalLockKey(UUID teenId, String key) {
        return "GOAL-LOCK:" + teenId + ":" + key;
    }

    static String goalUnlockKey(UUID teenId, String key) {
        return "GOAL-UNLOCK:" + teenId + ":" + key;
    }

    static String goalCloseKey(UUID goalId) {
        return "GOAL-CLOSE-" + goalId;
    }

    static String penaltyKey(UUID taskId) {
        return "PENALTY-TASK-" + taskId;
    }

    static String payoutKey(UUID payoutId) {
        return "PAYOUT-" + payoutId;
    }
}
