package kz.tiyn.ledger.wallet;

import java.util.UUID;
import kz.tiyn.identity.FamilyDirectory;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import kz.tiyn.platform.paging.Cursor;
import kz.tiyn.platform.paging.Page;
import kz.tiyn.platform.paging.PageRequest;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.platform.security.Role;
import org.springframework.stereotype.Service;

@Service
class WalletHistoryService {

    private final LedgerStore ledger;
    private final FamilyDirectory family;

    WalletHistoryService(LedgerStore ledger, FamilyDirectory family) {
        this.ledger = ledger;
        this.family = family;
    }

    Page<EntryView> entries(Principal caller, UUID teenId, PageRequest page) {
        UUID owner = walletOwner(caller, teenId);
        return ledger.pageForTeen(owner, Cursor.hashOf("wallet-entries"), page);
    }

    private UUID walletOwner(Principal caller, UUID teenId) {
        if (caller.role() == Role.TEEN) {
            if (teenId != null && !teenId.equals(caller.userId())) {
                throw new LedgerException(LedgerError.FORBIDDEN);
            }
            return caller.userId();
        }
        if (teenId == null) {
            throw new LedgerException(LedgerError.TEEN_REQUIRED);
        }
        if (!family.areLinked(caller.userId(), teenId)) {
            throw new LedgerException(LedgerError.FORBIDDEN);
        }
        return teenId;
    }
}
