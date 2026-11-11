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
    private final JoinAttemptStore joinAttempts;
    private final JoinRequestStore joinRequests;
    private final TransactionTemplate tx;
    private final Clock clock;

    FamilyService(
            AccountDirectory accounts,
            LinkStore links,
            JoinAttemptStore joinAttempts,
            JoinRequestStore joinRequests,
            TransactionTemplate tx,
            Clock clock) {
        this.accounts = accounts;
        this.links = links;
        this.joinAttempts = joinAttempts;
        this.joinRequests = joinRequests;
        this.tx = tx;
        this.clock = clock;
    }

    JoinOutcome join(UUID teenId, String rawCode) {
        User teen = accounts.requireUser(teenId);
        registerJoinAttempt(teenId);
        User parent = accounts.requireParentByJoinCode(rawCode);
        JoinOutcome outcome = needsApproval(parent, teen) ? requestApproval(parent, teen) : link(parent, teen);
        clearJoinAttempts(teenId);
        return outcome;
    }

    private boolean needsApproval(User parent, User teen) {
        return parent.joinApprovalRequired() && !links.existsActive(parent.id(), teen.id());
    }

    private JoinOutcome link(User parent, User teen) {
        ConflictRetry.run(() -> linkOnce(parent, teen), conflict());
        return new JoinOutcome.Linked(parent);
    }

    private JoinOutcome requestApproval(User parent, User teen) {
        JoinRequest request = ConflictRetry.run(() -> requestOnce(parent, teen), conflict());
        return new JoinOutcome.Pending(request.id(), parent);
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

    private Void linkOnce(User parent, User teen) {
        return tx.execute(status -> {
            FamilyLink link =
                    links.findForUpdate(parent.id(), teen.id()).orElseGet(() -> FamilyLink.between(parent, teen));
            link.link();
            links.save(link);
            return null;
        });
    }

    private JoinRequest requestOnce(User parent, User teen) {
        return tx.execute(
                status -> joinRequests.findPending(parent.id(), teen.id()).orElseGet(() -> {
                    JoinRequest request = JoinRequest.submit(UUID.randomUUID(), parent, teen);
                    joinRequests.insert(request);
                    return request;
                }));
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
