package kz.tiyn.notification.push;

import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import kz.tiyn.notification.push.error.NotificationError;
import kz.tiyn.notification.push.error.NotificationException;
import org.springframework.stereotype.Service;

@Service
class DeviceService {

    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final Set<String> LOCALES = Set.of(Messages.RU, Messages.KK);

    private final DeviceStore devices;
    private final Clock clock;

    DeviceService(DeviceStore devices, Clock clock) {
        this.devices = devices;
        this.clock = clock;
    }

    void register(UUID userId, String token, String platform, String locale) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new NotificationException(NotificationError.INVALID_TOKEN);
        }
        devices.upsert(userId, token.trim(), parsePlatform(platform), parseLocale(locale), clock.instant());
    }

    void unregister(UUID userId, String token) {
        if (token != null && !token.isBlank()) {
            devices.deleteOwn(userId, token.trim());
        }
    }

    private static Platform parsePlatform(String raw) {
        try {
            return Platform.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new NotificationException(NotificationError.INVALID_PLATFORM);
        }
    }

    private static String parseLocale(String raw) {
        String locale = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!LOCALES.contains(locale)) {
            throw new NotificationException(NotificationError.INVALID_LOCALE);
        }
        return locale;
    }
}
