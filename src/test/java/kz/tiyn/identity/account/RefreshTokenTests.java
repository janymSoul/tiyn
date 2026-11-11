package kz.tiyn.identity.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenTests {

    private static final Duration TTL = Duration.ofDays(30);
    private static final Instant ISSUED_AT = Instant.parse("2030-01-01T00:00:00Z");

    private final SecureRandom random = new SecureRandom();

    private RefreshToken.Issued issueAt(Instant now) {
        return RefreshToken.issue(UUID.randomUUID(), UUID.randomUUID(), now, TTL, random);
    }

    @Test
    void secretIsAtLeast256BitsOfUrlSafeBase64AndDiffersEveryTime() {
        RefreshToken.Issued first = issueAt(ISSUED_AT);
        RefreshToken.Issued second = issueAt(ISSUED_AT);

        assertThat(first.secret()).matches("[A-Za-z0-9_-]{43,}");
        assertThat(first.secret()).isNotEqualTo(second.secret());
    }

    @Test
    void onlyTheSha256HashOfTheSecretIsKept() {
        RefreshToken.Issued issued = issueAt(ISSUED_AT);

        assertThat(issued.token().hash()).matches("[0-9a-f]{64}").isNotEqualTo(issued.secret());
        assertThat(issued.token().hash()).isEqualTo(RefreshToken.hashOf(issued.secret()));
        assertThat(RefreshToken.hashOf("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void tokenIsUsableUntilItsExpiryAccordingToTheClock() {
        RefreshToken token = issueAt(ISSUED_AT).token();
        Clock justBefore = Clock.fixed(ISSUED_AT.plus(TTL).minusSeconds(1), ZoneOffset.UTC);
        Clock atExpiry = Clock.fixed(ISSUED_AT.plus(TTL), ZoneOffset.UTC);
        Clock afterExpiry = Clock.fixed(ISSUED_AT.plus(TTL).plusSeconds(1), ZoneOffset.UTC);

        assertThat(token.expiresAt()).isEqualTo(ISSUED_AT.plus(TTL));
        assertThat(token.isUsableAt(justBefore.instant())).isTrue();
        assertThat(token.isUsableAt(atExpiry.instant())).isFalse();
        assertThat(token.isUsableAt(afterExpiry.instant())).isFalse();
    }

    @Test
    void aUsedTokenIsAReplayAndNeverUsableAgain() {
        RefreshToken fresh = issueAt(ISSUED_AT).token();
        RefreshToken used = restored(fresh, ISSUED_AT.plusSeconds(5), null);

        assertThat(fresh.isReplay()).isFalse();
        assertThat(used.isReplay()).isTrue();
        assertThat(used.isUsableAt(ISSUED_AT.plusSeconds(10))).isFalse();
    }

    @Test
    void aRevokedTokenIsNotUsableAndNotAReplay() {
        RefreshToken fresh = issueAt(ISSUED_AT).token();
        RefreshToken revoked = restored(fresh, null, ISSUED_AT.plusSeconds(5));

        assertThat(revoked.isReplay()).isFalse();
        assertThat(revoked.isUsableAt(ISSUED_AT.plusSeconds(10))).isFalse();
    }

    private static RefreshToken restored(RefreshToken token, Instant usedAt, Instant revokedAt) {
        return RefreshToken.restore(
                token.id(), token.userId(), token.familyId(), token.hash(), token.expiresAt(), usedAt, revokedAt);
    }
}
