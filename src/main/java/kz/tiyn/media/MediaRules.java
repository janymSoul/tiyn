package kz.tiyn.media;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

final class MediaRules {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png");

    private MediaRules() {}

    static String requireAllowedType(String contentType) {
        if (contentType == null) {
            throw new InvalidMediaException("unsupported content type");
        }
        String normalised = contentType.toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(normalised)) {
            throw new InvalidMediaException("unsupported content type");
        }
        return normalised;
    }

    static byte[] readLimited(InputStream body) {
        try {
            byte[] bytes = body.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new InvalidMediaException("file too large");
            }
            if (bytes.length == 0) {
                throw new InvalidMediaException("empty file");
            }
            return bytes;
        } catch (IOException e) {
            throw new InvalidMediaException("unreadable file");
        }
    }
}
