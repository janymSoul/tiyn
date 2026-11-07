package kz.tiyn.ledger.wallet;

import static kz.tiyn.platform.jooq.ledger.Tables.LEDGER_ENTRIES;
import static kz.tiyn.platform.jooq.ledger.Tables.SAVINGS_GOALS;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.platform.jooq.ledger.tables.records.SavingsGoalsRecord;
import kz.tiyn.platform.paging.Cursor;
import kz.tiyn.platform.paging.Page;
import kz.tiyn.platform.paging.PageRequest;
import kz.tiyn.platform.time.Instants;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
class GoalStore {

    static final int OVERVIEW_LIMIT = 100;

    private final DSLContext dsl;

    GoalStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    void insert(Goal goal) {
        dsl.insertInto(SAVINGS_GOALS)
                .set(SAVINGS_GOALS.ID, goal.id())
                .set(SAVINGS_GOALS.TEEN_ID, goal.teenId())
                .set(SAVINGS_GOALS.CURRENCY, goal.currency())
                .set(SAVINGS_GOALS.NAME, goal.name())
                .set(SAVINGS_GOALS.TARGET_AMOUNT, goal.targetAmount())
                .set(SAVINGS_GOALS.STATE, goal.state().name())
                .execute();
    }

    void save(Goal goal) {
        dsl.update(SAVINGS_GOALS)
                .set(SAVINGS_GOALS.STATE, goal.state().name())
                .where(SAVINGS_GOALS.ID.eq(goal.id()))
                .execute();
    }

    Optional<Goal> find(UUID id) {
        return dsl.selectFrom(SAVINGS_GOALS).where(SAVINGS_GOALS.ID.eq(id)).fetchOptional(GoalStore::toGoal);
    }

    long saved(UUID goalId) {
        return dsl.select(DSL.coalesce(DSL.sum(LEDGER_ENTRIES.LOCKED_DELTA), BigDecimal.ZERO))
                .from(LEDGER_ENTRIES)
                .where(LEDGER_ENTRIES.REF_ID.eq(goalId))
                .fetchSingle()
                .value1()
                .longValueExact();
    }

    List<GoalView> listRecentForTeen(UUID teenId) {
        Field<BigDecimal> saved = savedAmount();
        return dsl.select(SAVINGS_GOALS.asterisk(), saved)
                .from(SAVINGS_GOALS)
                .where(SAVINGS_GOALS.TEEN_ID.eq(teenId))
                .orderBy(SAVINGS_GOALS.CREATED_AT.desc(), SAVINGS_GOALS.ID)
                .limit(OVERVIEW_LIMIT)
                .fetch(r ->
                        new GoalView(toGoal(r.into(SAVINGS_GOALS)), r.get(saved).longValueExact()));
    }

    Page<GoalView> pageForTeen(UUID teenId, String filterHash, PageRequest request) {
        Field<BigDecimal> saved = savedAmount();
        var ordered = dsl.select(SAVINGS_GOALS.asterisk(), saved)
                .from(SAVINGS_GOALS)
                .where(SAVINGS_GOALS.TEEN_ID.eq(teenId))
                .orderBy(SAVINGS_GOALS.CREATED_AT.desc(), SAVINGS_GOALS.ID.desc());
        Optional<Cursor> after = request.after(filterHash);
        List<Record> fetched = after.isPresent()
                ? ordered.seek(
                                Instants.toUtc(after.get().sortKey()),
                                after.get().id())
                        .limit(request.fetchSize())
                        .fetch()
                : ordered.limit(request.fetchSize()).fetch();
        return Page.of(
                        fetched,
                        request,
                        r -> new Cursor(
                                Instants.fromUtc(r.get(SAVINGS_GOALS.CREATED_AT)), r.get(SAVINGS_GOALS.ID), filterHash))
                .map(r ->
                        new GoalView(toGoal(r.into(SAVINGS_GOALS)), r.get(saved).longValueExact()));
    }

    private static Field<BigDecimal> savedAmount() {
        return DSL.field(DSL.select(DSL.coalesce(DSL.sum(LEDGER_ENTRIES.LOCKED_DELTA), BigDecimal.ZERO))
                .from(LEDGER_ENTRIES)
                .where(LEDGER_ENTRIES.REF_ID.eq(SAVINGS_GOALS.ID)));
    }

    private static Goal toGoal(SavingsGoalsRecord r) {
        return Goal.restore(
                r.getId(),
                r.getTeenId(),
                r.getCurrency(),
                r.getName(),
                r.getTargetAmount(),
                GoalState.valueOf(r.getState()));
    }
}
