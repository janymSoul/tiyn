package kz.tiyn.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import kz.tiyn.platform.security.AccessTokenIssuer;
import kz.tiyn.platform.security.JwtProperties;
import kz.tiyn.platform.security.Role;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class SessionServiceTests {

    private static final Duration REFRESH_TTL = Duration.ofDays(30);
    private static final Instant ISSUED_AT = Instant.parse("2030-01-01T00:00:00Z");

    private final RefreshTokenStore tokens = mock(RefreshTokenStore.class);
    private final UserStore users = mock(UserStore.class);
    private final AccessTokenIssuer accessTokens = mock(AccessTokenIssuer.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final JwtProperties properties =
            new JwtProperties("0123456789abcdef0123456789abcdef", Duration.ofHours(1), REFRESH_TTL);
    private final SecureRandom random = new SecureRandom();
    private final User user = User.restore(UUID.randomUUID(), "sub", Role.TEEN, null, "Dana", false);

    private SessionService serviceAt(Instant now) {
        when(tx.execute(any()))
                .thenAnswer(call -> call.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        return new SessionService(
                tokens, users, accessTokens, properties, tx, random, Clock.fixed(now, ZoneOffset.UTC));
    }

    private RefreshToken.Issued storedToken() {
        RefreshToken.Issued issued = RefreshToken.issue(user.id(), UUID.randomUUID(), ISSUED_AT, REFRESH_TTL, random);
        when(tokens.findByHash(issued.token().hash())).thenReturn(Optional.of(issued.token()));
        return issued;
    }

    @Test
    void openIssuesATokenThatExpiresAfterTheConfiguredRefreshTtl() {
        SessionService service = serviceAt(ISSUED_AT);

        String secret = service.open(user);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(tokens).insert(saved.capture());
        assertThat(saved.getValue().expiresAt()).isEqualTo(ISSUED_AT.plus(REFRESH_TTL));
        assertThat(saved.getValue().hash()).isEqualTo(RefreshToken.hashOf(secret));
    }

    @Test
    void refreshJustBeforeExpiryRotatesTheToken() {
        RefreshToken.Issued issued = storedToken();
        when(tokens.markUsed(any(), any())).thenReturn(true);
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(accessTokens.issue(user.id(), Role.TEEN)).thenReturn("access");

        var result = serviceAt(ISSUED_AT.plus(REFRESH_TTL).minusSeconds(1)).refresh(issued.secret());

        assertThat(result.token()).isEqualTo("access");
        assertThat(result.refreshToken()).isNotEqualTo(issued.secret());
        verify(tokens).insert(any());
    }

    @Test
    void refreshAtExpiryIsRejectedWithoutTouchingTheStoredToken() {
        RefreshToken.Issued issued = storedToken();
        SessionService service = serviceAt(ISSUED_AT.plus(REFRESH_TTL));

        assertThatThrownBy(() -> service.refresh(issued.secret()))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.error())
                        .isEqualTo(IdentityError.INVALID_REFRESH_TOKEN));
        verify(tokens, never()).markUsed(any(), any());
        verify(tokens, never()).revokeFamily(any(), any());
    }

    @Test
    void aTokenWhoseOwnerNoLongerExistsIsRejected() {
        RefreshToken.Issued issued = storedToken();
        when(tokens.markUsed(any(), any())).thenReturn(true);
        when(users.findById(user.id())).thenReturn(Optional.empty());
        SessionService service = serviceAt(ISSUED_AT.plusSeconds(1));

        assertThatThrownBy(() -> service.refresh(issued.secret())).isInstanceOf(IdentityException.class);
        verify(tokens, never()).insert(any());
    }
}
