package kz.tiyn.identity.family;

import static kz.tiyn.platform.jooq.identity.Tables.JOIN_REQUESTS;
import static kz.tiyn.platform.jooq.identity.Tables.USERS;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.platform.jooq.identity.tables.records.JoinRequestsRecord;
import kz.tiyn.platform.paging.Cursor;
import kz.tiyn.platform.paging.Page;
import kz.tiyn.platform.paging.PageRequest;
import kz.tiyn.platform.security.Role;
import kz.tiyn.platform.time.Instants;
import kz.tiyn.platform.tx.ConflictException;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
class JoinRequestStore {

    private static final String PENDING = JoinRequestState.PENDING.name();

    private final DSLContext dsl;
    private final ApplicationEventPublisher events;

    JoinRequestStore(DSLContext dsl, ApplicationEventPublisher events) {
        this.dsl = dsl;
        this.events = events;
    }

    Optional<JoinRequest> findForUpdate(UUID id) {
        return dsl.selectFrom(JOIN_REQUESTS)
                .where(JOIN_REQUESTS.ID.eq(id))
                .forUpdate()
                .fetchOptional()
                .map(JoinRequestStore::toRequest);
    }

    Optional<JoinRequest> findPending(UUID parentId, UUID teenId) {
        return dsl.selectFrom(JOIN_REQUESTS)
                .where(pendingOf(parentId).and(JOIN_REQUESTS.TEEN_ID.eq(teenId)))
                .fetchOptional()
                .map(JoinRequestStore::toRequest);
    }

    List<JoinRequest> findPendingForUpdate(UUID userId, Role role) {
        var self = role == Role.PARENT ? JOIN_REQUESTS.PARENT_ID : JOIN_REQUESTS.TEEN_ID;
        return dsl.selectFrom(JOIN_REQUESTS)
                .where(self.eq(userId).and(JOIN_REQUESTS.STATE.eq(PENDING)))
                .orderBy(JOIN_REQUESTS.ID)
                .forUpdate()
                .fetch(JoinRequestStore::toRequest);
    }

    Page<PendingJoinRequest> pagePending(UUID parentId, String filterHash, PageRequest request) {
        var ordered = dsl.select(JOIN_REQUESTS.ID, JOIN_REQUESTS.TEEN_ID, USERS.DISPLAY_NAME, JOIN_REQUESTS.CREATED_AT)
                .from(JOIN_REQUESTS)
                .join(USERS)
                .on(USERS.ID.eq(JOIN_REQUESTS.TEEN_ID))
                .where(pendingOf(parentId))
                .orderBy(JOIN_REQUESTS.CREATED_AT.desc(), JOIN_REQUESTS.ID.desc());
        Optional<Cursor> after = request.after(filterHash);
        List<? extends Record> fetched = after.isPresent()
                ? ordered.seek(
                                Instants.toUtc(after.get().sortKey()),
                                after.get().id())
                        .limit(request.fetchSize())
                        .fetch()
                : ordered.limit(request.fetchSize()).fetch();
        return Page.of(
                fetched.stream().map(JoinRequestStore::toPending).toList(),
                request,
                p -> new Cursor(p.createdAt(), p.id(), filterHash));
    }

    void insert(JoinRequest request) {
        try {
            dsl.insertInto(JOIN_REQUESTS)
                    .set(JOIN_REQUESTS.ID, request.id())
                    .set(JOIN_REQUESTS.PARENT_ID, request.parentId())
                    .set(JOIN_REQUESTS.TEEN_ID, request.teenId())
                    .set(JOIN_REQUESTS.STATE, request.state().name())
                    .execute();
        } catch (DuplicateKeyException e) {
            throw new ConflictException(e);
        }
        request.pullEvents().forEach(events::publishEvent);
    }

    void save(JoinRequest request) {
        dsl.update(JOIN_REQUESTS)
                .set(JOIN_REQUESTS.STATE, request.state().name())
                .set(JOIN_REQUESTS.DECIDED_AT, Instants.toUtc(request.decidedAt()))
                .where(JOIN_REQUESTS
                        .ID
                        .eq(request.id())
                        .and(JOIN_REQUESTS.STATE.ne(request.state().name())))
                .execute();
    }

    private static Condition pendingOf(UUID parentId) {
        return JOIN_REQUESTS.PARENT_ID.eq(parentId).and(JOIN_REQUESTS.STATE.eq(PENDING));
    }

    private static JoinRequest toRequest(JoinRequestsRecord r) {
        return JoinRequest.restore(
                r.getId(),
                r.getParentId(),
                r.getTeenId(),
                JoinRequestState.valueOf(r.getState()),
                Instants.fromUtc(r.getDecidedAt()));
    }

    private static PendingJoinRequest toPending(Record r) {
        return new PendingJoinRequest(
                r.get(JOIN_REQUESTS.ID),
                r.get(JOIN_REQUESTS.TEEN_ID),
                r.get(USERS.DISPLAY_NAME),
                Instants.fromUtc(r.get(JOIN_REQUESTS.CREATED_AT)));
    }
}
