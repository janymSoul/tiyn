package kz.tiyn.identity.family;

import java.time.Clock;
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
class FamilyService {

    private final AccountDirectory accounts;
    private final LinkStore links;
    private final TransactionTemplate tx;
    private final Clock clock;

    FamilyService(AccountDirectory accounts, LinkStore links, TransactionTemplate tx, Clock clock) {
        this.accounts = accounts;
        this.links = links;
        this.tx = tx;
        this.clock = clock;
    }

    User join(UUID teenId, String rawCode) {
        User teen = accounts.requireUser(teenId);
        User parent = accounts.requireParentByJoinCode(rawCode);
        ConflictRetry.run(() -> linkOnce(parent, teen), conflict());
        return parent;
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

    private Void linkOnce(User parent, User teen) {
        return tx.execute(status -> {
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
