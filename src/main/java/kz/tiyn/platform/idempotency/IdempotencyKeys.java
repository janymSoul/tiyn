package kz.tiyn.platform.idempotency;

import static kz.tiyn.platform.jooq.platform.Tables.IDEMPOTENCY_KEYS;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.platform.time.Instants;
import kz.tiyn.platform.web.ApiException;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeys {

    private final DSLContext dsl;
    private final Clock clock;

    IdempotencyKeys(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    public Optional<UUID> claimOrFindPrevious(
            UUID userId, IdempotencyContext context, String operation, UUID resourceId, Object request) {
        String hash = fingerprint(operation, request);
        int claimed = dsl.insertInto(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.USER_ID, userId)
                .set(IDEMPOTENCY_KEYS.KEY, context.key())
                .set(IDEMPOTENCY_KEYS.OPERATION, operation)
                .set(IDEMPOTENCY_KEYS.REQUEST_HASH, hash)
                .set(IDEMPOTENCY_KEYS.RESOURCE_ID, resourceId)
                .set(IDEMPOTENCY_KEYS.CREATED_AT, Instants.toUtc(clock.instant()))
                .onConflictDoNothing()
                .execute();
        if (claimed == 1) {
            return Optional.empty();
        }
        var previous = dsl.selectFrom(IDEMPOTENCY_KEYS)
                .where(IDEMPOTENCY_KEYS.USER_ID.eq(userId))
                .and(IDEMPOTENCY_KEYS.KEY.eq(context.key()))
                .fetchOne();
        if (previous == null || !previous.getRequestHash().equals(hash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, IdempotencyContextResolver.INVALID_KEY);
        }
        return Optional.of(previous.getResourceId());
    }

    private static String fingerprint(String operation, Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((operation + '\n' + request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
