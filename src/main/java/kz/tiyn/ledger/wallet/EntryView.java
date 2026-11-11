package kz.tiyn.ledger.wallet;

import java.time.Instant;

record EntryView(LedgerEntry entry, Instant createdAt) {

    long availableEffect() {
        return entry.balanceDelta() - entry.lockedDelta();
    }
}
