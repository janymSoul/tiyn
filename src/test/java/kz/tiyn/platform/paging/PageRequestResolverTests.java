package kz.tiyn.platform.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import kz.tiyn.platform.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class PageRequestResolverTests {

    private final PageRequestResolver resolver = new PageRequestResolver();

    private PageRequest resolve(String... parameters) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        for (int i = 0; i < parameters.length; i += 2) {
            request.setParameter(parameters[i], parameters[i + 1]);
        }
        return (PageRequest) resolver.resolveArgument(null, null, new ServletWebRequest(request), null);
    }

    private void assertRejected(HttpStatus status, String code, String... parameters) {
        assertThatThrownBy(() -> resolve(parameters)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    @Test
    void withoutParametersTheLimitIsFiftyAndThereIsNoCursor() {
        PageRequest request = resolve();

        assertThat(request.limit()).isEqualTo(50);
        assertThat(request.cursor()).isNull();
        assertThat(request.fetchSize()).isEqualTo(51);
    }

    @Test
    void theLimitMayBeAnythingFromOneToOneHundred() {
        assertThat(resolve("limit", "1").limit()).isEqualTo(1);
        assertThat(resolve("limit", "100").limit()).isEqualTo(100);
    }

    @Test
    void aLimitOutsideOneToOneHundredIsABadRequest() {
        assertRejected(HttpStatus.BAD_REQUEST, "invalid_request", "limit", "0");
        assertRejected(HttpStatus.BAD_REQUEST, "invalid_request", "limit", "101");
        assertRejected(HttpStatus.BAD_REQUEST, "invalid_request", "limit", "-5");
        assertRejected(HttpStatus.BAD_REQUEST, "invalid_request", "limit", "ten");
        assertRejected(HttpStatus.BAD_REQUEST, "invalid_request", "limit", "");
        assertRejected(HttpStatus.BAD_REQUEST, "invalid_request", "limit", "99999999999");
    }

    @Test
    void aValidCursorIsDecodedAndAMalformedOneIsInvalidCursor() {
        Cursor cursor = new Cursor(Instant.parse("2026-11-07T10:15:30Z"), UUID.randomUUID(), Cursor.hashOf("x"));

        assertThat(resolve("cursor", cursor.encode()).cursor()).isEqualTo(cursor);
        assertRejected(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_cursor", "cursor", "garbage");
    }

    @Test
    void aBlankCursorMeansTheFirstPage() {
        assertThat(resolve("cursor", " ").cursor()).isNull();
    }

    @Test
    void theLimitIsCheckedBeforeTheCursor() {
        assertRejected(HttpStatus.BAD_REQUEST, "invalid_request", "limit", "0", "cursor", "garbage");
    }
}
