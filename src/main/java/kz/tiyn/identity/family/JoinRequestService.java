package kz.tiyn.identity.family;

import java.time.Clock;
import java.util.UUID;
import kz.tiyn.identity.account.AccountDirectory;
import kz.tiyn.identity.account.User;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import kz.tiyn.platform.paging.Cursor;
import kz.tiyn.platform.paging.Page;
import kz.tiyn.platform.paging.PageRequest;
import kz.tiyn.platform.security.Role;
import kz.tiyn.platform.tx.ConflictRetry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class JoinRequestService {

    private static final String PENDING_LIST_HASH = Cursor.hashOf("join-requests", JoinRequestState.PENDING);

    private final AccountDirectory accounts;
    private final LinkStore links;
    private final JoinRequestStore joinRequests;
    private final TransactionTemplate tx;
    private final Clock clock;

    JoinRequestService(
            AccountDirectory accounts,
            LinkStore links,
            JoinRequestStore joinRequests,
            TransactionTemplate tx,
            Clock clock) {
        this.accounts = accounts;
        this.links = links;
        this.joinRequests = joinRequests;
        this.tx = tx;
        this.clock = clock;
    }

    Page<PendingJoinRequest> pending(UUID userId, Role role, PageRequest page) {
        if (role != Role.PARENT) {
            throw new IdentityException(IdentityError.FORBIDDEN);
        }
        return joinRequests.pagePending(userId, PENDING_LIST_HASH, page);
    }

    JoinDecision approve(UUID parentId, UUID requestId) {
        JoinRequest request = ConflictRetry.run(
                () -> approveOnce(parentId, requestId), () -> new IdentityException(IdentityError.CONFLICT));
        return decision(request);
    }

    JoinDecision decline(UUID parentId, UUID requestId) {
        return decision(declineOnce(parentId, requestId));
    }

    private JoinRequest declineOnce(UUID parentId, UUID requestId) {
        return tx.execute(status -> {
            JoinRequest locked = lock(requestId);
            locked.declineBy(parentId, clock.instant());
            joinRequests.save(locked);
            return locked;
        });
    }

    void withdraw(UUID teenId, UUID requestId) {
        tx.executeWithoutResult(status -> {
            JoinRequest locked = lock(requestId);
            locked.withdrawBy(teenId, clock.instant());
            joinRequests.save(locked);
        });
    }

    private JoinRequest approveOnce(UUID parentId, UUID requestId) {
        return tx.execute(status -> {
            JoinRequest request = lock(requestId);
            boolean firstApproval = request.isPending();
            request.approveBy(parentId, clock.instant());
            if (firstApproval) {
                link(accounts.requireUser(request.parentId()), accounts.requireUser(request.teenId()));
            }
            joinRequests.save(request);
            return request;
        });
    }

    private void link(User parent, User teen) {
        FamilyLink link = links.findForUpdate(parent.id(), teen.id()).orElseGet(() -> FamilyLink.between(parent, teen));
        link.link();
        links.save(link);
    }

    private JoinRequest lock(UUID requestId) {
        return joinRequests.findForUpdate(requestId).orElseThrow(() -> new IdentityException(IdentityError.NOT_FOUND));
    }

    private JoinDecision decision(JoinRequest request) {
        return new JoinDecision(request.id(), request.state(), accounts.requireUser(request.teenId()));
    }
}
