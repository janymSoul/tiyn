package kz.tiyn.identity.account;

import static kz.tiyn.platform.jooq.identity.Tables.USERS;

import java.util.Optional;
import java.util.UUID;
import kz.tiyn.platform.jooq.identity.tables.records.UsersRecord;
import kz.tiyn.platform.security.Role;
import kz.tiyn.platform.tx.ConflictException;
import org.jooq.DSLContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
class UserStore {

    private final DSLContext dsl;

    UserStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    Optional<User> findById(UUID id) {
        return dsl.selectFrom(USERS).where(USERS.ID.eq(id)).fetchOptional().map(UserStore::toUser);
    }

    Optional<User> findForUpdate(UUID id) {
        return dsl.selectFrom(USERS)
                .where(USERS.ID.eq(id))
                .forUpdate()
                .fetchOptional()
                .map(UserStore::toUser);
    }

    Optional<User> findByIdpSub(String idpSub) {
        return dsl.selectFrom(USERS)
                .where(USERS.IDP_SUB.eq(idpSub))
                .fetchOptional()
                .map(UserStore::toUser);
    }

    Optional<User> findByJoinCode(String joinCode) {
        return dsl.selectFrom(USERS)
                .where(USERS.JOIN_CODE.eq(joinCode))
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

    void updateJoinCode(User user) {
        try {
            dsl.update(USERS)
                    .set(USERS.JOIN_CODE, user.joinCode())
                    .where(USERS.ID.eq(user.id()))
                    .execute();
        } catch (DuplicateKeyException e) {
            throw new ConflictException(e);
        }
    }

    private static User toUser(UsersRecord r) {
        return User.restore(r.getId(), r.getIdpSub(), Role.valueOf(r.getRole()), r.getJoinCode(), r.getDisplayName());
    }
}
