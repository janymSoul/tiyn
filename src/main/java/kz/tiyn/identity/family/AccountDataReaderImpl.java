package kz.tiyn.identity.family;

import static kz.tiyn.platform.jooq.identity.Tables.FAMILY_LINKS;
import static kz.tiyn.platform.jooq.identity.Tables.JOIN_REQUESTS;
import static kz.tiyn.platform.jooq.identity.Tables.USERS;

import java.util.List;
import java.util.UUID;
import kz.tiyn.identity.AccountDataReader;
import kz.tiyn.identity.family.dto.FamilyLinkExport;
import kz.tiyn.identity.family.dto.JoinRequestExport;
import kz.tiyn.identity.family.dto.ProfileExport;
import kz.tiyn.platform.export.ExportProperties;
import kz.tiyn.platform.export.ExportWriter;
import kz.tiyn.platform.export.KeysetScan;
import kz.tiyn.platform.export.KeysetScan.Position;
import kz.tiyn.platform.security.Role;
import kz.tiyn.platform.time.Instants;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountDataReaderImpl implements AccountDataReader {

    private final DSLContext dsl;
    private final KeysetScan scan;

    AccountDataReaderImpl(DSLContext dsl, ExportProperties properties) {
        this.dsl = dsl;
        this.scan = new KeysetScan(properties.pageSize());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void writeTo(UUID userId, Role role, ExportWriter out) {
        out.field("profile", profile(userId));
        Side side = Side.of(userId, role);
        out.array("familyLinks", items -> scanLinks(side, items));
        out.array("joinRequests", items -> scanJoinRequests(userId, role, items));
    }

    private ProfileExport profile(UUID userId) {
        return dsl.select(
                        USERS.ID,
                        USERS.ROLE,
                        USERS.DISPLAY_NAME,
                        USERS.CREATED_AT,
                        USERS.JOIN_CODE,
                        USERS.JOIN_APPROVAL_REQUIRED)
                .from(USERS)
                .where(USERS.ID.eq(userId))
                .fetchOptional(r -> new ProfileExport(
                        r.get(USERS.ID),
                        r.get(USERS.ROLE),
                        r.get(USERS.DISPLAY_NAME),
                        Instants.fromUtc(r.get(USERS.CREATED_AT)),
                        r.get(USERS.JOIN_CODE),
                        Role.PARENT.name().equals(r.get(USERS.ROLE)) ? r.get(USERS.JOIN_APPROVAL_REQUIRED) : null))
                .orElse(null);
    }

    private void scanLinks(Side side, ExportWriter.Items items) {
        scan.scan(
                (after, limit) -> linksAfter(side, after, limit),
                link -> new Position(link.linkedAt(), link.id()),
                page -> page.forEach(items::add));
    }

    private List<FamilyLinkExport> linksAfter(Side side, Position after, int limit) {
        var ordered = dsl.select(USERS.ID, USERS.DISPLAY_NAME, FAMILY_LINKS.CREATED_AT, FAMILY_LINKS.REMOVED_AT)
                .from(FAMILY_LINKS)
                .join(USERS)
                .on(USERS.ID.eq(side.other()))
                .where(side.self().eq(side.userId()))
                .orderBy(FAMILY_LINKS.CREATED_AT.desc(), USERS.ID.desc());
        var seeked = after == null
                ? ordered.limit(limit)
                : ordered.seek(Instants.toUtc(after.sortKey()), after.id()).limit(limit);
        return seeked.fetch(r -> new FamilyLinkExport(
                r.get(USERS.ID),
                r.get(USERS.DISPLAY_NAME),
                Instants.fromUtc(r.get(FAMILY_LINKS.CREATED_AT)),
                Instants.fromUtc(r.get(FAMILY_LINKS.REMOVED_AT))));
    }

    private void scanJoinRequests(UUID userId, Role role, ExportWriter.Items items) {
        scan.scan(
                (after, limit) -> joinRequestsAfter(userId, role, after, limit),
                request -> new Position(request.createdAt(), request.id()),
                page -> page.forEach(items::add));
    }

    private List<JoinRequestExport> joinRequestsAfter(UUID userId, Role role, Position after, int limit) {
        boolean parent = role == Role.PARENT;
        var self = parent ? JOIN_REQUESTS.PARENT_ID : JOIN_REQUESTS.TEEN_ID;
        var other = parent ? JOIN_REQUESTS.TEEN_ID : JOIN_REQUESTS.PARENT_ID;
        var ordered = dsl.select(
                        JOIN_REQUESTS.ID,
                        USERS.ID,
                        USERS.DISPLAY_NAME,
                        JOIN_REQUESTS.STATE,
                        JOIN_REQUESTS.CREATED_AT,
                        JOIN_REQUESTS.DECIDED_AT)
                .from(JOIN_REQUESTS)
                .join(USERS)
                .on(USERS.ID.eq(other))
                .where(self.eq(userId))
                .orderBy(JOIN_REQUESTS.CREATED_AT.desc(), JOIN_REQUESTS.ID.desc());
        var seeked = after == null
                ? ordered.limit(limit)
                : ordered.seek(Instants.toUtc(after.sortKey()), after.id()).limit(limit);
        return seeked.fetch(r -> new JoinRequestExport(
                r.get(JOIN_REQUESTS.ID),
                parent ? null : r.get(USERS.ID),
                parent ? null : r.get(USERS.DISPLAY_NAME),
                parent ? r.get(USERS.ID) : null,
                parent ? r.get(USERS.DISPLAY_NAME) : null,
                r.get(JOIN_REQUESTS.STATE),
                Instants.fromUtc(r.get(JOIN_REQUESTS.CREATED_AT)),
                Instants.fromUtc(r.get(JOIN_REQUESTS.DECIDED_AT))));
    }

    private record Side(UUID userId, Field<UUID> self, Field<UUID> other) {

        static Side of(UUID userId, Role role) {
            return role == Role.PARENT
                    ? new Side(userId, FAMILY_LINKS.PARENT_ID, FAMILY_LINKS.TEEN_ID)
                    : new Side(userId, FAMILY_LINKS.TEEN_ID, FAMILY_LINKS.PARENT_ID);
        }
    }
}
