package kz.tiyn.ledger.wallet;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.identity.FamilyDirectory;
import kz.tiyn.ledger.wallet.dto.AdjustmentRequest;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import kz.tiyn.platform.idempotency.IdempotencyContext;
import kz.tiyn.platform.idempotency.IdempotencyKeys;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.platform.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class AdjustmentService {

    private static final String OPERATION = "ledger.adjust";

    private final WalletStore wallets;
    private final LedgerStore ledger;
    private final FamilyDirectory family;
    private final TransactionTemplate tx;
    private final IdempotencyKeys idempotency;
    private final LedgerProperties properties;

    AdjustmentService(
            WalletStore wallets,
            LedgerStore ledger,
            FamilyDirectory family,
            TransactionTemplate tx,
            IdempotencyKeys idempotency,
            LedgerProperties properties) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.family = family;
        this.tx = tx;
        this.idempotency = idempotency;
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
            UUID entryId = UUID.randomUUID();
            Optional<UUID> previous = idempotency.claimOrFindPrevious(
                    caller.userId(), context, OPERATION, entryId, List.of(teenId, adjustment));
            if (previous.isPresent()) {
                return ledger.findAdjustment(previous.get()).orElseThrow();
            }
            ledger.append(wallet.adjust(entryId, caller.userId(), adjustment));
            wallets.save(wallet);
            return ledger.findAdjustment(entryId).orElseThrow();
        });
    }

    private void requireLinked(UUID parentId, UUID teenId) {
        if (!family.areLinked(parentId, teenId)) {
            throw new LedgerException(LedgerError.FORBIDDEN);
        }
    }
}
