package kz.tiyn.identity.family;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.tiyn.identity.account.AccountDirectory;
import kz.tiyn.identity.account.User;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import kz.tiyn.platform.security.Role;
import kz.tiyn.platform.tx.ConflictRetry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class FamilyService implements FamilyExit {

    private final AccountDirectory accounts;
    private final LinkStore links;
    private final JoinAttemptStore joinAttempts;
    private final TransactionTemplate tx;
    private final Clock clock;

    FamilyService(
            AccountDirectory accounts,
            LinkStore links,
            JoinAttemptStore joinAttempts,
            TransactionTemplate tx,
            Clock clock) {
        this.accounts = accounts;
        this.links = links;
        this.joinAttempts = joinAttempts;
        this.tx = tx;
        this.clock = clock;
    }

    User join(UUID teenId, String rawCode) {
        User teen = accounts.requireUser(teenId);
        registerJoinAttempt(teenId);
        User parent = accounts.requireParentByJoinCode(rawCode);
        ConflictRetry.run(() -> linkOnce(parent, teen), conflict());
        clearJoinAttempts(teenId);
        return parent;
    }

    private void registerJoinAttempt(UUID teenId) {
        tx.executeWithoutResult(status -> {
            JoinAttempts attempts = joinAttempts.lockOrCreate(teenId, clock.instant());
            attempts.register(clock.instant());
            joinAttempts.save(attempts);
        });
    }

    private void clearJoinAttempts(UUID teenId) {
        tx.executeWithoutResult(status -> joinAttempts.clear(teenId));
    }

    List<FamilyMember> members(UUID userId, Role role) {
        return links.activeMembers(userId, role);
    }

    void unlink(UUID parentId, UUID teenId) {
        User parent = accounts.requireUser(parentId);
        if (parent.role() != Role.PARENT) {
            throw new IdentityException(IdentityError.FORBIDDEN);
        }
        ConflictRetry.run(() -> unlinkOnce(parentId, teenId), conflict());
    }

    @Override
    public void unlinkAll(User user) {
        Instant now = clock.instant();
        links.findActiveForUpdate(user.id(), user.role()).forEach(link -> {
            link.unlink(now);
            links.save(link);
        });
    }

    private Void linkOnce(User parent, User teen) {
        return tx.execute(status -> {
            accounts.shareActiveUser(parent.id())
                    .orElseThrow(() -> new IdentityException(IdentityError.INVALID_JOIN_CODE));
            accounts.shareActiveUser(teen.id()).orElseThrow(() -> new IdentityException(IdentityError.UNAUTHENTICATED));
            FamilyLink link =
                    links.findForUpdate(parent.id(), teen.id()).orElseGet(() -> FamilyLink.between(parent, teen));
            link.link();
            links.save(link);
            return null;
        });
    }

    private Void unlinkOnce(UUID parentId, UUID teenId) {
        return tx.execute(status -> {
            FamilyLink link = links.findForUpdate(parentId, teenId)
                    .orElseThrow(() -> new IdentityException(IdentityError.NOT_FOUND));
            link.unlink(clock.instant());
            links.save(link);
            return null;
        });
    }

    private static java.util.function.Supplier<IdentityException> conflict() {
        return () -> new IdentityException(IdentityError.CONFLICT);
    }
}
