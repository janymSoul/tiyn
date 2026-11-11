package kz.tiyn.identity.deletion;

import java.util.Objects;
import java.util.UUID;
import kz.tiyn.identity.OpenPayoutCheck;
import kz.tiyn.identity.account.AccountDirectory;
import kz.tiyn.identity.account.User;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import kz.tiyn.identity.family.FamilyExit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final AccountDirectory accounts;
    private final FamilyExit family;
    private final OpenPayoutCheck openPayouts;
    private final TransactionTemplate tx;

    AccountDeletionService(
            AccountDirectory accounts, FamilyExit family, OpenPayoutCheck openPayouts, TransactionTemplate tx) {
        this.accounts = accounts;
        this.family = family;
        this.openPayouts = openPayouts;
        this.tx = tx;
    }

    void delete(UUID userId) {
        User deleted = Objects.requireNonNull(tx.execute(status -> deleteInTransaction(userId)));
        log.info("Account deleted userId={} role={}", deleted.id(), deleted.role());
    }

    private User deleteInTransaction(UUID userId) {
        User user =
                accounts.lockActiveUser(userId).orElseThrow(() -> new IdentityException(IdentityError.UNAUTHENTICATED));
        if (openPayouts.hasOpenPayout(user.id(), user.role())) {
            throw new IdentityException(IdentityError.ACCOUNT_HAS_OPEN_PAYOUT);
        }
        family.unlinkAll(user);
        accounts.delete(user);
        return user;
    }
}
