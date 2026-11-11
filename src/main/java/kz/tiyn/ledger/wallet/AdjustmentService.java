package kz.tiyn.ledger.wallet;

import java.util.Optional;
import java.util.UUID;
import kz.tiyn.identity.FamilyDirectory;
import kz.tiyn.ledger.wallet.dto.AdjustmentRequest;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import kz.tiyn.platform.idempotency.IdempotencyContext;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.platform.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class AdjustmentService {

    private final WalletStore wallets;
    private final LedgerStore ledger;
    private final FamilyDirectory family;
    private final TransactionTemplate tx;
    private final LedgerProperties properties;

    AdjustmentService(
            WalletStore wallets,
            LedgerStore ledger,
            FamilyDirectory family,
            TransactionTemplate tx,
            LedgerProperties properties) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.family = family;
        this.tx = tx;
        this.properties = properties;
    }

    AdjustmentView adjust(Principal caller, IdempotencyContext context, UUID teenId, AdjustmentRequest request) {
        if (caller.role() != Role.PARENT) {
            throw new LedgerException(LedgerError.FORBIDDEN);
        }
        requireLinked(caller.userId(), teenId);
        Adjustment adjustment = Adjustment.of(request, properties.maxAdjustmentAmount());
        return tx.execute(status -> {
            wallets.ensure(teenId, Wallet.KZT);
            Wallet wallet = wallets.findForUpdate(teenId, Wallet.KZT).orElseThrow();
            requireLinked(caller.userId(), teenId);
            String ledgerKey = LedgerEntry.adjustmentKey(caller.userId(), context.key());
            Optional<LedgerEntry> replay = ledger.findByKey(ledgerKey);
            if (replay.isPresent()) {
                requireSameRequest(replay.get(), teenId, adjustment);
                return ledger.findAdjustment(replay.get().id()).orElseThrow();
            }
            UUID entryId = UUID.randomUUID();
            ledger.append(wallet.adjust(entryId, caller.userId(), adjustment, ledgerKey));
            wallets.save(wallet);
            return ledger.findAdjustment(entryId).orElseThrow();
        });
    }

    private static void requireSameRequest(LedgerEntry original, UUID teenId, Adjustment adjustment) {
        boolean same = original.teenId().equals(teenId)
                && original.kind() == adjustment.entryKind()
                && original.balanceDelta() == adjustment.balanceDelta()
                && adjustment.reason().equals(original.note());
        if (!same) {
            throw new LedgerException(LedgerError.INVALID_IDEMPOTENCY_KEY);
        }
    }

    private void requireLinked(UUID parentId, UUID teenId) {
        if (!family.areLinked(parentId, teenId)) {
            throw new LedgerException(LedgerError.FORBIDDEN);
        }
    }
}
