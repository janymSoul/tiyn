package kz.tiyn.ledger.wallet;

import java.util.List;
import java.util.UUID;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.platform.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class GoalService {

    private static final int MAX_KEY_LENGTH = 100;

    private final GoalStore goals;
    private final WalletStore wallets;
    private final LedgerStore ledger;
    private final TransactionTemplate tx;

    GoalService(GoalStore goals, WalletStore wallets, LedgerStore ledger, TransactionTemplate tx) {
        this.goals = goals;
        this.wallets = wallets;
        this.ledger = ledger;
        this.tx = tx;
    }

    GoalView create(Principal caller, String name, Long targetAmount) {
        requireTeen(caller);
        if (targetAmount == null) {
            throw new LedgerException(LedgerError.INVALID_AMOUNT);
        }
        Goal goal = Goal.create(UUID.randomUUID(), caller.userId(), Wallet.KZT, name, targetAmount);
        tx.executeWithoutResult(status -> {
            wallets.ensure(caller.userId(), Wallet.KZT);
            goals.insert(goal);
        });
        return new GoalView(goal, 0);
    }

    List<GoalView> list(Principal caller) {
        requireTeen(caller);
        return goals.listForTeen(caller.userId());
    }

    GoalView get(Principal caller, UUID id) {
        requireTeen(caller);
        Goal goal = owned(caller, id);
        return new GoalView(goal, goals.saved(id));
    }

    GoalView lock(Principal caller, UUID id, Long amount, String key) {
        requireTeen(caller);
        requireKey(key);
        requireAmount(amount);
        String ledgerKey = LedgerEntry.goalLockKey(caller.userId(), key);
        return tx.execute(status -> {
            Wallet wallet = lockedWallet(caller);
            Goal goal = owned(caller, id);
            long saved = goals.saved(id);
            var replay = ledger.findByKey(ledgerKey);
            if (replay.isPresent()) {
                requireSameRequest(replay.get(), id, amount);
                return new GoalView(goal, saved);
            }
            goal.requireCanSave(saved, amount);
            ledger.append(wallet.lockForGoal(UUID.randomUUID(), id, amount, ledgerKey));
            wallets.save(wallet);
            return new GoalView(goal, saved + amount);
        });
    }

    GoalView unlock(Principal caller, UUID id, Long amount, String key) {
        requireTeen(caller);
        requireKey(key);
        requireAmount(amount);
        String ledgerKey = LedgerEntry.goalUnlockKey(caller.userId(), key);
        return tx.execute(status -> {
            Wallet wallet = lockedWallet(caller);
            Goal goal = owned(caller, id);
            long saved = goals.saved(id);
            var replay = ledger.findByKey(ledgerKey);
            if (replay.isPresent()) {
                requireSameRequest(replay.get(), id, -amount);
                return new GoalView(goal, saved);
            }
            ledger.append(wallet.unlockFromGoal(UUID.randomUUID(), id, amount, saved, ledgerKey));
            wallets.save(wallet);
            return new GoalView(goal, saved - amount);
        });
    }

    GoalView close(Principal caller, UUID id) {
        requireTeen(caller);
        return tx.execute(status -> {
            Wallet wallet = lockedWallet(caller);
            Goal goal = owned(caller, id);
            if (goal.state() == GoalState.CLOSED) {
                return new GoalView(goal, 0);
            }
            long saved = goals.saved(id);
            if (saved > 0) {
                ledger.append(wallet.unlockFromGoal(UUID.randomUUID(), id, saved, saved, LedgerEntry.goalCloseKey(id)));
                wallets.save(wallet);
            }
            goal.close();
            goals.save(goal);
            return new GoalView(goal, 0);
        });
    }

    private Wallet lockedWallet(Principal caller) {
        wallets.ensure(caller.userId(), Wallet.KZT);
        return wallets.findForUpdate(caller.userId(), Wallet.KZT).orElseThrow();
    }

    private Goal owned(Principal caller, UUID id) {
        return goals.find(id)
                .filter(goal -> goal.teenId().equals(caller.userId()))
                .orElseThrow(() -> new LedgerException(LedgerError.NOT_FOUND));
    }

    private static void requireSameRequest(LedgerEntry original, UUID goalId, long lockedDelta) {
        if (!goalId.equals(original.refId()) || original.lockedDelta() != lockedDelta) {
            throw new LedgerException(LedgerError.INVALID_IDEMPOTENCY_KEY);
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new LedgerException(LedgerError.INVALID_IDEMPOTENCY_KEY);
        }
    }

    private static void requireAmount(Long amount) {
        if (amount == null) {
            throw new LedgerException(LedgerError.INVALID_AMOUNT);
        }
    }

    private static void requireTeen(Principal caller) {
        if (caller.role() != Role.TEEN) {
            throw new LedgerException(LedgerError.FORBIDDEN);
        }
    }
}
