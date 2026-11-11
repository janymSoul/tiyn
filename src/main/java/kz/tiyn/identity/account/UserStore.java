package kz.tiyn.identity.account;

import static kz.tiyn.platform.jooq.identity.Tables.USERS;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.platform.jooq.identity.tables.records.UsersRecord;
import kz.tiyn.platform.security.Role;
import kz.tiyn.platform.time.Instants;
import kz.tiyn.platform.tx.ConflictException;
import org.jooq.DSLContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
class UserStore {

    private final DSLContext dsl;
    private final ApplicationEventPublisher events;

    UserStore(DSLContext dsl, ApplicationEventPublisher events) {
        this.dsl = dsl;
        this.events = events;
    }

    Optional<User> findById(UUID id) {
        return dsl.selectFrom(USERS)
                .where(USERS.ID.eq(id).and(USERS.DELETED_AT.isNull()))
                .fetchOptional()
                .map(UserStore::toUser);
    }

    Optional<User> findActiveForUpdate(UUID id) {
        return dsl.selectFrom(USERS)
                .where(USERS.ID.eq(id).and(USERS.DELETED_AT.isNull()))
                .forUpdate()
                .fetchOptional()
                .map(UserStore::toUser);
    }

    Optional<User> findActiveForShare(UUID id) {
        return dsl.selectFrom(USERS)
                .where(USERS.ID.eq(id).and(USERS.DELETED_AT.isNull()))
                .forShare()
                .fetchOptional()
                .map(UserStore::toUser);
    }

    boolean isDeleted(UUID id) {
        return dsl.fetchExists(dsl.selectOne().from(USERS).where(USERS.ID.eq(id).and(USERS.DELETED_AT.isNotNull())));
    }

    Optional<User> findByIdpSub(String idpSub) {
        return dsl.selectFrom(USERS)
                .where(USERS.IDP_SUB.eq(idpSub).and(USERS.DELETED_AT.isNull()))
                .fetchOptional()
                .map(UserStore::toUser);
    }

    Optional<User> findByJoinCode(String joinCode) {
        return dsl.selectFrom(USERS)
                .where(USERS.JOIN_CODE.eq(joinCode).and(USERS.DELETED_AT.isNull()))
                .fetchOptional()
                .map(UserStore::toUser);
    }

    void insert(User user) {
        try {
            dsl.insertInto(USERS)
                    .set(USERS.ID, user.id())
                    .set(USERS.IDP_SUB, user.idpSub())
                    .set(USERS.ROLE, user.role().name())
                    .set(USERS.JOIN_CODE, user.joinCode())
                    .set(USERS.DISPLAY_NAME, user.displayName())
                    .execute();
        } catch (DuplicateKeyException e) {
            throw new ConflictException(e);
        }
    }

    void save(User user) {
        List<Object> raised = user.pullEvents();
        if (raised.isEmpty()) {
            return;
        }
        dsl.update(USERS)
                .set(USERS.IDP_SUB, user.idpSub())
                .set(USERS.JOIN_CODE, user.joinCode())
                .set(USERS.DISPLAY_NAME, user.displayName())
                .set(USERS.DELETED_AT, Instants.toUtc(user.deletedAt()))
                .where(USERS.ID.eq(user.id()))
                .execute();
        raised.forEach(events::publishEvent);
    }

    private static User toUser(UsersRecord r) {
        return User.restore(r.getId(), r.getIdpSub(), Role.valueOf(r.getRole()), r.getJoinCode(), r.getDisplayName());
    }
}
