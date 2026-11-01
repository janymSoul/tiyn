package kz.tiyn.identity.app;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import kz.tiyn.identity.domain.FamilyLink;
import kz.tiyn.identity.domain.IdentityError;
import kz.tiyn.identity.domain.IdentityException;
import kz.tiyn.identity.domain.JoinCode;
import kz.tiyn.identity.domain.User;
import kz.tiyn.identity.google.GoogleIdentity;
import kz.tiyn.identity.google.GoogleVerifier;
import kz.tiyn.identity.store.ConflictException;
import kz.tiyn.identity.store.LinkStore;
import kz.tiyn.identity.store.UserStore;
import kz.tiyn.platform.security.AccessTokenIssuer;
import kz.tiyn.platform.security.Role;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdentityService {

    private static final int MAX_ATTEMPTS = 5;

    private final GoogleVerifier google;
    private final AccessTokenIssuer tokens;
    private final UserStore users;
    private final LinkStore links;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final SecureRandom random;
    private final Clock clock;

    IdentityService(
            GoogleVerifier google,
            AccessTokenIssuer tokens,
            UserStore users,
            LinkStore links,
            TransactionTemplate tx,
            ApplicationEventPublisher events,
            SecureRandom random,
            Clock clock) {
        this.google = google;
        this.tokens = tokens;
        this.users = users;
        this.links = links;
        this.tx = tx;
        this.events = events;
        this.random = random;
        this.clock = clock;
    }

    public SignInResult signIn(String idToken, String role, String displayName) {
        if (idToken == null || idToken.isBlank()) {
            throw new IdentityException(IdentityError.INVALID_IDENTITY);
        }
        GoogleIdentity identity = google.verify(idToken);
        User user = withRetry(() -> signInOnce(identity, role, displayName));
        return new SignInResult(tokens.issue(user.id(), user.role()), user);
    }

    public User me(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new IdentityException(IdentityError.UNAUTHENTICATED));
    }

    public String join(UUID teenId, String rawCode) {
        User teen = me(teenId);
        JoinCode code = JoinCode.parse(rawCode);
        User parent = users.findByJoinCode(code.value())
                .orElseThrow(() -> new IdentityException(IdentityError.INVALID_JOIN_CODE));
        withRetry(() -> linkOnce(parent, teen));
        return parent.displayName();
    }

    public void unlink(UUID parentId, UUID teenId) {
        User parent = me(parentId);
        if (parent.role() != Role.PARENT) {
            throw new IdentityException(IdentityError.FORBIDDEN);
        }
        withRetry(() -> unlinkOnce(parentId, teenId));
    }

    private User signInOnce(GoogleIdentity identity, String roleInput, String displayNameInput) {
        return tx.execute(status -> {
            Optional<User> existing = users.findByIdpSub(identity.subject());
            if (existing.isPresent()) {
                return existing.get();
            }
            Role role = parseRole(roleInput);
            String name = displayNameInput != null && !displayNameInput.isBlank() ? displayNameInput : identity.name();
            JoinCode code = role == Role.PARENT ? JoinCode.generate(random) : null;
            User user = User.register(UUID.randomUUID(), identity.subject(), role, name, code);
            users.insert(user);
            return user;
        });
    }

    private Void linkOnce(User parent, User teen) {
        return tx.execute(status -> {
            FamilyLink link =
                    links.findForUpdate(parent.id(), teen.id()).orElseGet(() -> FamilyLink.between(parent, teen));
            link.link();
            saveAndPublish(link);
            return null;
        });
    }

    private Void unlinkOnce(UUID parentId, UUID teenId) {
        return tx.execute(status -> {
            FamilyLink link = links.findForUpdate(parentId, teenId)
                    .orElseThrow(() -> new IdentityException(IdentityError.NOT_FOUND));
            link.unlink(clock.instant());
            saveAndPublish(link);
            return null;
        });
    }

    private void saveAndPublish(FamilyLink link) {
        List<Object> raised = link.pullEvents();
        if (raised.isEmpty()) {
            return;
        }
        links.save(link);
        raised.forEach(events::publishEvent);
    }

    private <T> T withRetry(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (ConflictException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new IdentityException(IdentityError.CONFLICT);
                }
            }
        }
    }

    private static Role parseRole(String raw) {
        try {
            return Role.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IdentityException(IdentityError.INVALID_ROLE);
        }
    }
}
