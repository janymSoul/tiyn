package kz.tiyn.identity.account;

import static kz.tiyn.platform.jooq.identity.Tables.REFRESH_TOKENS;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.platform.jooq.identity.tables.records.RefreshTokensRecord;
import kz.tiyn.platform.time.Instants;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
class RefreshTokenStore {

    private final DSLContext dsl;

    RefreshTokenStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    Optional<RefreshToken> findByHash(String hash) {
        return dsl.selectFrom(REFRESH_TOKENS)
                .where(REFRESH_TOKENS.TOKEN_HASH.eq(hash))
                .fetchOptional()
                .map(RefreshTokenStore::toToken);
    }

    void insert(RefreshToken token) {
        dsl.insertInto(REFRESH_TOKENS)
                .set(REFRESH_TOKENS.ID, token.id())
                .set(REFRESH_TOKENS.USER_ID, token.userId())
                .set(REFRESH_TOKENS.FAMILY_ID, token.familyId())
                .set(REFRESH_TOKENS.TOKEN_HASH, token.hash())
                .set(REFRESH_TOKENS.EXPIRES_AT, Instants.toUtc(token.expiresAt()))
                .execute();
    }

    boolean markUsed(UUID id, Instant now) {
        return dsl.update(REFRESH_TOKENS)
                        .set(REFRESH_TOKENS.USED_AT, Instants.toUtc(now))
                        .where(REFRESH_TOKENS.ID.eq(id))
                        .and(REFRESH_TOKENS.USED_AT.isNull())
                        .and(REFRESH_TOKENS.REVOKED_AT.isNull())
                        .execute()
                == 1;
    }

    int revokeFamily(UUID familyId, Instant now) {
        return dsl.update(REFRESH_TOKENS)
                .set(REFRESH_TOKENS.REVOKED_AT, Instants.toUtc(now))
                .where(REFRESH_TOKENS.FAMILY_ID.eq(familyId))
                .and(REFRESH_TOKENS.REVOKED_AT.isNull())
                .execute();
    }

    int deleteExpired(Instant cutoff, int limit) {
        return dsl.deleteFrom(REFRESH_TOKENS)
                .where(REFRESH_TOKENS.ID.in(dsl.select(REFRESH_TOKENS.ID)
                        .from(REFRESH_TOKENS)
                        .where(REFRESH_TOKENS.EXPIRES_AT.lt(Instants.toUtc(cutoff)))
                        .limit(limit)))
                .execute();
    }

    private static RefreshToken toToken(RefreshTokensRecord r) {
        return RefreshToken.restore(
                r.getId(),
                r.getUserId(),
                r.getFamilyId(),
                r.getTokenHash(),
                Instants.fromUtc(r.getExpiresAt()),
                Instants.fromUtc(r.getUsedAt()),
                Instants.fromUtc(r.getRevokedAt()));
    }
}
