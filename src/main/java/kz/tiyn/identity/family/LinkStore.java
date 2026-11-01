package kz.tiyn.identity.family;

import static kz.tiyn.platform.jooq.identity.Tables.FAMILY_LINKS;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.platform.jooq.identity.tables.records.FamilyLinksRecord;
import kz.tiyn.platform.tx.ConflictException;
import org.jooq.DSLContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
class LinkStore {

    private final DSLContext dsl;

    LinkStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    Optional<FamilyLink> findForUpdate(UUID parentId, UUID teenId) {
        return dsl.selectFrom(FAMILY_LINKS)
                .where(FAMILY_LINKS.PARENT_ID.eq(parentId).and(FAMILY_LINKS.TEEN_ID.eq(teenId)))
                .forUpdate()
                .fetchOptional()
                .map(LinkStore::toLink);
    }

    boolean existsActive(UUID parentId, UUID teenId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(FAMILY_LINKS)
                .where(FAMILY_LINKS.PARENT_ID.eq(parentId))
                .and(FAMILY_LINKS.TEEN_ID.eq(teenId))
                .and(FAMILY_LINKS.REMOVED_AT.isNull()));
    }

    void save(FamilyLink link) {
        if (update(link) == 0) {
            insert(link);
        }
    }

    private void insert(FamilyLink link) {
        try {
            dsl.insertInto(FAMILY_LINKS)
                    .set(FAMILY_LINKS.PARENT_ID, link.parentId())
                    .set(FAMILY_LINKS.TEEN_ID, link.teenId())
                    .set(FAMILY_LINKS.GENERATION, link.generation())
                    .execute();
        } catch (DuplicateKeyException e) {
            throw new ConflictException(e);
        }
    }

    private int update(FamilyLink link) {
        OffsetDateTime removedAt =
                link.removedAt() == null ? null : OffsetDateTime.ofInstant(link.removedAt(), ZoneOffset.UTC);
        return dsl.update(FAMILY_LINKS)
                .set(FAMILY_LINKS.GENERATION, link.generation())
                .set(FAMILY_LINKS.REMOVED_AT, removedAt)
                .where(FAMILY_LINKS.PARENT_ID.eq(link.parentId()).and(FAMILY_LINKS.TEEN_ID.eq(link.teenId())))
                .execute();
    }

    private static FamilyLink toLink(FamilyLinksRecord r) {
        Instant removedAt = r.getRemovedAt() == null ? null : r.getRemovedAt().toInstant();
        return FamilyLink.restore(r.getParentId(), r.getTeenId(), r.getGeneration(), removedAt);
    }
}
