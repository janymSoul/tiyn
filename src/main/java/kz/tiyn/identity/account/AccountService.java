package kz.tiyn.identity.account;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.identity.account.dto.SignInResult;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import kz.tiyn.identity.google.GoogleIdentity;
import kz.tiyn.identity.google.GoogleUnavailableException;
import kz.tiyn.identity.google.GoogleVerifier;
import kz.tiyn.identity.google.InvalidGoogleTokenException;
import kz.tiyn.platform.security.AccessTokenIssuer;
import kz.tiyn.platform.security.Role;
import kz.tiyn.platform.tx.ConflictRetry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class AccountService implements AccountDirectory {

    private final GoogleVerifier google;
    private final AccessTokenIssuer tokens;
    private final UserStore users;
    private final TransactionTemplate tx;
    private final SecureRandom random;

    AccountService(
            GoogleVerifier google,
            AccessTokenIssuer tokens,
            UserStore users,
            TransactionTemplate tx,
            SecureRandom random) {
        this.google = google;
        this.tokens = tokens;
        this.users = users;
        this.tx = tx;
        this.random = random;
    }

    SignInResult signIn(String idToken, String role, String displayName) {
        if (idToken == null || idToken.isBlank()) {
            throw new IdentityException(IdentityError.INVALID_IDENTITY);
        }
        GoogleIdentity identity = verify(idToken);
        User user = ConflictRetry.run(
                () -> signInOnce(identity, role, displayName), () -> new IdentityException(IdentityError.CONFLICT));
        return new SignInResult(tokens.issue(user.id(), user.role()), user);
    }

    User changeJoinApproval(UUID userId, boolean required) {
        return tx.execute(status -> {
            User user = users.findByIdForUpdate(userId)
                    .orElseThrow(() -> new IdentityException(IdentityError.UNAUTHENTICATED));
            if (required) {
                user.requireApprovalOfJoins();
            } else {
                user.allowDirectJoins();
            }
            users.saveJoinApproval(user);
            return user;
        });
    }

    private GoogleIdentity verify(String idToken) {
        try {
            return google.verify(idToken);
        } catch (InvalidGoogleTokenException e) {
            throw new IdentityException(IdentityError.INVALID_IDENTITY);
        } catch (GoogleUnavailableException e) {
            throw new IdentityException(IdentityError.IDENTITY_PROVIDER_UNAVAILABLE);
        }
    }

    @Override
    public User requireUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new IdentityException(IdentityError.UNAUTHENTICATED));
    }

    @Override
    public User requireParentByJoinCode(String rawCode) {
        JoinCode code = JoinCode.parse(rawCode);
        return users.findByJoinCode(code.value())
                .orElseThrow(() -> new IdentityException(IdentityError.INVALID_JOIN_CODE));
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

    private static Role parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IdentityException(IdentityError.ROLE_REQUIRED);
        }
        try {
            return Role.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IdentityException(IdentityError.INVALID_ROLE);
        }
    }
}
