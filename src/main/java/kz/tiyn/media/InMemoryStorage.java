package kz.tiyn.media;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tiyn.media.storage", havingValue = "memory")
class InMemoryStorage implements Storage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public String put(String scope, String contentType, InputStream body) {
        String type = MediaRules.requireAllowedType(contentType);
        byte[] clean = ImageSanitizer.strip(type, MediaRules.readLimited(body));
        String objectKey = "proofs/" + scope + "/" + UUID.randomUUID();
        objects.put(objectKey, clean);
        return objectKey;
    }

    @Override
    public URI presignView(String objectKey) {
        return URI.create("memory://" + objectKey);
    }
}
