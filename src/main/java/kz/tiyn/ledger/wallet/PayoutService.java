package kz.tiyn.ledger.wallet;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import kz.tiyn.identity.FamilyDirectory;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.platform.security.Role;
import kz.tiyn.platform.tx.ConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PayoutService {

    private static final int MAX_KEY_LENGTH = 100;

    private final PayoutStore payouts;
    private final WalletStore wallets;
    private final LedgerStore ledger;
    private final FamilyDirectory family;
    private final TransactionTemplate tx;
    private final LedgerProperties properties;
    private final Clock clock;

    PayoutService(
            PayoutStore payouts,
            WalletStore wallets,
            LedgerStore ledger,
            FamilyDirectory family,
            TransactionTemplate tx,
            LedgerProperties properties,
            Clock clock) {
        this.payouts = payouts;
        this.wallets = wallets;
        this.ledger = ledger;
        this.family = family;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    Payout request(Principal caller, UUID parentId, Long amount, String key) {
        requireRole(caller, Role.TEEN);
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new LedgerException(LedgerError.INVALID_IDEMPOTENCY_KEY);
        }
        if (parentId == null) {
            throw new LedgerException(LedgerError.PARENT_REQUIRED);
        }
        if (amount == null) {
            throw new LedgerException(LedgerError.INVALID_AMOUNT);
        }
        if (!family.areLinked(parentId, caller.userId())) {
            throw new LedgerException(LedgerError.FORBIDDEN);
        }
        String scopedKey = caller.userId() + ":" + key;
        try {
            return tx.execute(status -> {
                wallets.ensure(caller.userId(), Wallet.KZT);
                Wallet wallet =
                        wallets.findForUpdate(caller.userId(), Wallet.KZT).orElseThrow();
                var replay = payouts.findByKey(scopedKey);
                if (replay.isPresent()) {
                    return replay.get();
                }
                if (payouts.hasOpen(caller.userId())) {
                    throw new LedgerException(LedgerError.PAYOUT_ALREADY_OPEN);
                }
                Payout payout = Payout.request(
                        UUID.randomUUID(),
                        caller.userId(),
                        parentId,
                        amount,
                        Wallet.KZT,
                        scopedKey,
                        properties.minPayoutAmount(),
                        clock.instant());
                wallet.requireCanRequest(amount);
                payouts.insert(payout);
                return payout;
            });
        } catch (ConflictException e) {
            throw new LedgerException(LedgerError.PAYOUT_ALREADY_OPEN);
        }
    }

    Payout fulfil(Principal caller, UUID id) {
        requireRole(caller, Role.PARENT);
        Payout visible = get(caller, id);
        requireStillLinked(caller, visible);
        return tx.execute(status -> {
            Wallet wallet =
                    wallets.findForUpdate(visible.teenId(), visible.currency()).orElseThrow();
            Payout payout = payouts.findForUpdate(id).orElseThrow(() -> new LedgerException(LedgerError.NOT_FOUND));
            boolean wasOpen = payout.state() == PayoutState.REQUESTED;
            payout.fulfil(caller.userId(), clock.instant());
            if (wasOpen) {
                ledger.append(wallet.debitForPayout(UUID.randomUUID(), payout.id(), payout.amount()));
                wallets.save(wallet);
                payouts.save(payout);
            }
            return payout;
        });
    }

    Payout cancel(Principal caller, UUID id) {
        requireRole(caller, Role.TEEN);
        return resolve(caller, id, (payout, now) -> payout.cancelByTeen(caller.userId(), now));
    }

    Payout decline(Principal caller, UUID id) {
        requireRole(caller, Role.PARENT);
        return resolve(caller, id, (payout, now) -> payout.decline(caller.userId(), now));
    }

    boolean expire(UUID id, Instant now) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Payout visible = payouts.find(id).orElse(null);
            if (visible == null) {
                return false;
            }
            wallets.findForUpdate(visible.teenId(), visible.currency()).orElseThrow();
            Payout payout = payouts.findForUpdate(id).orElseThrow();
            payout.expire(now.minus(properties.payoutExpiry()), now);
            if (payout.state() != PayoutState.CANCELLED) {
                return false;
            }
            payouts.save(payout);
            return true;
        }));
    }

    void cancelAfterUnlink(UUID parentId, UUID teenId) {
        tx.executeWithoutResult(status -> {
            wallets.ensure(teenId, Wallet.KZT);
            wallets.findForUpdate(teenId, Wallet.KZT).orElseThrow();
            if (family.areLinked(parentId, teenId)) {
                return;
            }
            payouts.findOpen(teenId, parentId).ifPresent(payout -> {
                payout.cancelForUnlink(clock.instant());
                payouts.save(payout);
            });
        });
    }

    Payout get(Principal caller, UUID id) {
        Payout payout = payouts.find(id).orElseThrow(() -> new LedgerException(LedgerError.NOT_FOUND));
        requireParticipant(caller, payout);
        return payout;
    }

    List<Payout> list(Principal caller, PayoutState state) {
        return caller.role() == Role.TEEN
                ? payouts.listForTeen(caller.userId(), state)
                : payouts.listForParent(caller.userId(), state);
    }

    private Payout resolve(Principal caller, UUID id, BiConsumer<Payout, Instant> action) {
        Payout visible = get(caller, id);
        requireStillLinked(caller, visible);
        return tx.execute(status -> {
            wallets.findForUpdate(visible.teenId(), visible.currency()).orElseThrow();
            Payout payout = payouts.findForUpdate(id).orElseThrow(() -> new LedgerException(LedgerError.NOT_FOUND));
            action.accept(payout, clock.instant());
            payouts.save(payout);
            return payout;
        });
    }

    private void requireStillLinked(Principal caller, Payout payout) {
        if (caller.role() == Role.PARENT && !family.areLinked(caller.userId(), payout.teenId())) {
            throw new LedgerException(LedgerError.FORBIDDEN);
        }
    }

    private static void requireParticipant(Principal caller, Payout payout) {
        boolean participant = caller.role() == Role.TEEN
                ? payout.teenId().equals(caller.userId())
                : payout.parentId().equals(caller.userId());
        if (!participant) {
            throw new LedgerException(LedgerError.NOT_FOUND);
        }
    }

    private static void requireRole(Principal caller, Role role) {
        if (caller.role() != role) {
            throw new LedgerException(LedgerError.FORBIDDEN);
        }
    }
}
