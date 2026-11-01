package kz.tiyn.notification.push;

import java.util.UUID;

record DeviceToken(UUID id, UUID userId, String token, Platform platform, String locale) {}
