package kz.tiyn.identity.family;

import static kz.tiyn.platform.jooq.identity.Tables.FAMILY_LINKS;
import static kz.tiyn.platform.jooq.identity.Tables.USERS;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kz.tiyn.platform.jooq.identity.tables.records.FamilyLinksRecord;
import kz.tiyn.platform.security.Role;
import kz.tiyn.platform.time.Instants;
import kz.tiyn.platform.tx.ConflictException;
import org.jooq.DSLContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
class LinkStore {

    private final DSLContext dsl;
    private final ApplicationEventPublisher events;

    LinkStore(DSLContext dsl, ApplicationEventPublisher events) {
        this.dsl = dsl;
        this.events = events;
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

    Set<UUID> activeTeenIds(UUID parentId) {
        return Set.copyOf(dsl.select(FAMILY_LINKS.TEEN_ID)
                .from(FAMILY_LINKS)
                .where(FAMILY_LINKS.PARENT_ID.eq(parentId))
                .and(FAMILY_LINKS.REMOVED_AT.isNull())
                .fetch(FAMILY_LINKS.TEEN_ID));
    }

    List<FamilyMember> activeMembers(UUID userId, Role role) {
        boolean parent = role == Role.PARENT;
        var self = parent ? FAMILY_LINKS.PARENT_ID : FAMILY_LINKS.TEEN_ID;
        var other = parent ? FAMILY_LINKS.TEEN_ID : FAMILY_LINKS.PARENT_ID;
        return dsl.select(USERS.ID, USERS.ROLE, USERS.DISPLAY_NAME)
                .from(FAMILY_LINKS)
                .join(USERS)
                .on(USERS.ID.eq(other))
                .where(self.eq(userId).and(FAMILY_LINKS.REMOVED_AT.isNull()))
                .orderBy(USERS.DISPLAY_NAME, USERS.ID)
                .fetch(r ->
                        new FamilyMember(r.get(USERS.ID), Role.valueOf(r.get(USERS.ROLE)), r.get(USERS.DISPLAY_NAME)));
    }

    void save(FamilyLink link) {
        List<Object> raised = link.pullEvents();
        if (raised.isEmpty()) {
            return;
        }
        if (update(link) == 0) {
            insert(link);
        }
        raised.forEach(events::publishEvent);
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
        return dsl.update(FAMILY_LINKS)
                .set(FAMILY_LINKS.GENERATION, link.generation())
                .set(FAMILY_LINKS.REMOVED_AT, Instants.toUtc(link.removedAt()))
                .where(FAMILY_LINKS.PARENT_ID.eq(link.parentId()).and(FAMILY_LINKS.TEEN_ID.eq(link.teenId())))
                .execute();
    }

    private static FamilyLink toLink(FamilyLinksRecord r) {
        return FamilyLink.restore(
                r.getParentId(), r.getTeenId(), r.getGeneration(), Instants.fromUtc(r.getRemovedAt()));
    }
}
