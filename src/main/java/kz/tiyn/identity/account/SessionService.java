package kz.tiyn.identity.account;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.identity.account.dto.SignInResult;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import kz.tiyn.platform.security.AccessTokenIssuer;
import kz.tiyn.platform.security.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final RefreshTokenStore tokens;
    private final UserStore users;
    private final AccessTokenIssuer accessTokens;
    private final JwtProperties properties;
    private final TransactionTemplate tx;
    private final SecureRandom random;
    private final Clock clock;

    SessionService(
            RefreshTokenStore tokens,
            UserStore users,
            AccessTokenIssuer accessTokens,
            JwtProperties properties,
            TransactionTemplate tx,
            SecureRandom random,
            Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.tx = tx;
        this.random = random;
        this.clock = clock;
    }

    String open(User user) {
        return tx.execute(status -> issueIn(UUID.randomUUID(), user.id()));
    }

    SignInResult refresh(String secret) {
        Optional<SignInResult> rotated = Objects.requireNonNull(tx.execute(status -> rotate(secret)));
        return rotated.orElseThrow(() -> new IdentityException(IdentityError.INVALID_REFRESH_TOKEN));
    }

    void logout(String secret) {
        tx.executeWithoutResult(status -> findBySecret(secret).ifPresent(this::revokeFamily));
    }

    private Optional<SignInResult> rotate(String secret) {
        Optional<RefreshToken> found = findBySecret(secret);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken presented = found.get();
        if (presented.isReplay()) {
            revokeAfterReplay(presented);
            return Optional.empty();
        }
        if (!presented.isUsableAt(clock.instant())) {
            return Optional.empty();
        }
        if (!tokens.markUsed(presented.id(), clock.instant())) {
            revokeAfterReplay(presented);
            return Optional.empty();
        }
        return users.findById(presented.userId()).map(user -> {
            String next = issueIn(presented.familyId(), user.id());
            return new SignInResult(accessTokens.issue(user.id(), user.role()), next, user);
        });
    }

    private String issueIn(UUID familyId, UUID userId) {
        RefreshToken.Issued issued =
                RefreshToken.issue(userId, familyId, clock.instant(), properties.refreshTtl(), random);
        tokens.insert(issued.token());
        return issued.secret();
    }

    private Optional<RefreshToken> findBySecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByHash(RefreshToken.hashOf(secret));
    }

    private void revokeFamily(RefreshToken token) {
        tokens.revokeFamily(token.familyId(), clock.instant());
    }

    private void revokeAfterReplay(RefreshToken token) {
        int revoked = tokens.revokeFamily(token.familyId(), clock.instant());
        if (revoked > 0) {
            log.warn(
                    "refresh token reuse detected, family revoked userId={} familyId={} tokens={}",
                    token.userId(),
                    token.familyId(),
                    revoked);
        }
    }
}
