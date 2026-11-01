package kz.tiyn.media;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tiyn.media.storage", havingValue = "memory", matchIfMissing = true)
class InMemoryStorage implements Storage {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public String put(String scope, String contentType, InputStream body) {
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new InvalidMediaException("unsupported content type");
        }
        byte[] bytes = readLimited(body);
        String objectKey = "proofs/" + scope + "/" + UUID.randomUUID();
        objects.put(objectKey, bytes);
        return objectKey;
    }

    @Override
    public URI presignView(String objectKey) {
        return URI.create("memory://" + objectKey);
    }

    private static byte[] readLimited(InputStream body) {
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
