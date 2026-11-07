package kz.tiyn.platform.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import kz.tiyn.platform.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CursorTests {

    private static final Instant SORT_KEY = Instant.parse("2026-11-07T10:15:30.123456Z");
    private static final UUID ID = UUID.fromString("0b0a6c57-6a4e-4d0c-9d3e-5f3a1b2c3d4e");
    private static final String HASH = Cursor.hashOf("TEEN", "ASSIGNED");

    private static String encodePayload(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.code()).isEqualTo("invalid_cursor");
        });
    }

    @Test
    void aCursorSurvivesAnEncodeDecodeRoundTripWithMicrosecondPrecision() {
        Cursor cursor = new Cursor(SORT_KEY, ID, HASH);

        assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void theEncodedFormIsUrlSafeAndPaddingFree() {
        String encoded = new Cursor(SORT_KEY, ID, HASH).encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void garbageThatIsNotBase64IsInvalid() {
        assertInvalid(() -> Cursor.decode("not a cursor!"));
        assertInvalid(() -> Cursor.decode("a+b/c="));
        assertInvalid(() -> Cursor.decode(""));
        assertInvalid(() -> Cursor.decode(null));
    }

    @Test
    void base64ThatDoesNotCarryACursorIsInvalid() {
        assertInvalid(() -> Cursor.decode(encodePayload("hello")));
        assertInvalid(() -> Cursor.decode(encodePayload("1|only|three")));
        assertInvalid(() -> Cursor.decode(encodePayload("1|" + SORT_KEY + "|not-a-uuid|" + HASH)));
        assertInvalid(() -> Cursor.decode(encodePayload("1|yesterday|" + ID + "|" + HASH)));
        assertInvalid(() -> Cursor.decode(encodePayload("1|" + SORT_KEY + "|" + ID + "|" + HASH + "|extra")));
    }

    @Test
    void anIdThatIsNotInCanonicalFormIsInvalid() {
        assertInvalid(() -> Cursor.decode(encodePayload("1|" + SORT_KEY + "|1-1-1-1-1|" + HASH)));
        assertInvalid(() -> Cursor.decode(
                encodePayload("1|" + SORT_KEY + "|" + ID.toString().toUpperCase() + "|" + HASH)));
    }

    @Test
    void anUnknownVersionIsInvalid() {
        assertInvalid(() -> Cursor.decode(encodePayload("2|" + SORT_KEY + "|" + ID + "|" + HASH)));
        assertInvalid(() -> Cursor.decode(encodePayload("0|" + SORT_KEY + "|" + ID + "|" + HASH)));
        assertInvalid(() -> Cursor.decode(encodePayload("|" + SORT_KEY + "|" + ID + "|" + HASH)));
    }

    @Test
    void everyTruncationOfACursorIsRejectedOnceTheFiltersAreChecked() {
        String encoded = new Cursor(SORT_KEY, ID, HASH).encode();

        for (int length = 0; length < encoded.length(); length++) {
            String truncated = encoded.substring(0, length);
            assertInvalid(() -> new PageRequest(10, Cursor.decode(truncated)).after(HASH));
        }
    }

    @Test
    void anOversizedCursorIsInvalid() {
        assertInvalid(() -> Cursor.decode("A".repeat(Cursor.MAX_ENCODED_LENGTH + 1)));
    }

    @Test
    void theFilterHashDependsOnEveryFilterAndItsPosition() {
        assertThat(Cursor.hashOf("a", "b")).isEqualTo(Cursor.hashOf("a", "b"));
        assertThat(Cursor.hashOf("a", "b")).isNotEqualTo(Cursor.hashOf("a", "c"));
        assertThat(Cursor.hashOf("a", null)).isNotEqualTo(Cursor.hashOf(null, "a"));
        assertThat(Cursor.hashOf("ab", "c")).isNotEqualTo(Cursor.hashOf("a", "bc"));
        assertThat(Cursor.hashOf((Object) null)).isNotEqualTo(Cursor.hashOf("null", "null"));
    }

    @Test
    void aCursorReusedWithDifferentFiltersIsInvalid() {
        PageRequest request = new PageRequest(10, new Cursor(SORT_KEY, ID, Cursor.hashOf("ASSIGNED")));

        assertThat(request.after(Cursor.hashOf("ASSIGNED"))).isPresent();
        assertInvalid(() -> request.after(Cursor.hashOf("APPROVED")));
        assertInvalid(() -> request.after(Cursor.hashOf((Object) null)));
    }

    @Test
    void aFirstPageRequestHasNoCursorToCheck() {
        assertThat(new PageRequest(10, null).after(HASH)).isEmpty();
    }
}
