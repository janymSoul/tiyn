package kz.tiyn.notification.push;

import static kz.tiyn.platform.jooq.notification.Tables.DEVICE_TOKENS;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
class DeviceStore {

    private final DSLContext dsl;

    DeviceStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    void upsert(UUID userId, String token, Platform platform, String locale) {
        dsl.insertInto(DEVICE_TOKENS)
                .set(DEVICE_TOKENS.ID, UUID.randomUUID())
                .set(DEVICE_TOKENS.USER_ID, userId)
                .set(DEVICE_TOKENS.TOKEN, token)
                .set(DEVICE_TOKENS.PLATFORM, platform.name())
                .set(DEVICE_TOKENS.LOCALE, locale)
                .onConflict(DEVICE_TOKENS.TOKEN)
                .doUpdate()
                .set(DEVICE_TOKENS.USER_ID, userId)
                .set(DEVICE_TOKENS.PLATFORM, platform.name())
                .set(DEVICE_TOKENS.LOCALE, locale)
                .set(DEVICE_TOKENS.LAST_SEEN_AT, OffsetDateTime.now())
                .execute();
    }

    List<DeviceToken> findByUser(UUID userId) {
        return dsl.selectFrom(DEVICE_TOKENS)
                .where(DEVICE_TOKENS.USER_ID.eq(userId))
                .orderBy(DEVICE_TOKENS.CREATED_AT, DEVICE_TOKENS.ID)
                .fetch(r -> new DeviceToken(
                        r.getId(), r.getUserId(), r.getToken(), Platform.valueOf(r.getPlatform()), r.getLocale()));
    }

    void deleteOwn(UUID userId, String token) {
        dsl.deleteFrom(DEVICE_TOKENS)
                .where(DEVICE_TOKENS.USER_ID.eq(userId))
                .and(DEVICE_TOKENS.TOKEN.eq(token))
                .execute();
    }

    void deleteToken(String token) {
        dsl.deleteFrom(DEVICE_TOKENS).where(DEVICE_TOKENS.TOKEN.eq(token)).execute();
    }
}
