package kz.tiyn.platform.paging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import kz.tiyn.platform.web.ApiException;
import org.springframework.http.HttpStatus;

public record Cursor(Instant sortKey, UUID id, String filterHash) {

    static final String INVALID = "invalid_cursor";
    static final int VERSION = 1;
    static final int MAX_ENCODED_LENGTH = 256;
    private static final int HASH_LENGTH = 12;
    private static final String SEPARATOR = "|";
    private static final int PARTS = 4;

    public Cursor {
        Objects.requireNonNull(sortKey);
        Objects.requireNonNull(id);
        Objects.requireNonNull(filterHash);
    }

    public static String hashOf(Object... filters) {
        StringBuilder joined = new StringBuilder();
        for (Object filter : filters) {
            joined.append(filter).append('\u001f');
        }
        return HexFormat.of()
                .formatHex(sha256(joined.toString().getBytes(StandardCharsets.UTF_8)))
                .substring(0, HASH_LENGTH);
    }

    public String encode() {
        String payload = String.join(SEPARATOR, String.valueOf(VERSION), sortKey.toString(), id.toString(), filterHash);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String encoded) {
        if (encoded == null || encoded.length() > MAX_ENCODED_LENGTH) {
            throw invalid();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\" + SEPARATOR, -1);
            if (parts.length != PARTS
                    || !String.valueOf(VERSION).equals(parts[0])
                    || parts[3].length() != HASH_LENGTH) {
                throw invalid();
            }
            UUID id = UUID.fromString(parts[2]);
            if (!id.toString().equals(parts[2])) {
                throw invalid();
            }
            return new Cursor(Instant.parse(parts[1]), id, parts[3]);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static ApiException invalid() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, INVALID);
    }
}
