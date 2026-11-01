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
}
