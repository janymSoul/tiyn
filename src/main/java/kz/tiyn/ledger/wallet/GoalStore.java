package kz.tiyn.ledger.wallet;

import static kz.tiyn.platform.jooq.ledger.Tables.LEDGER_ENTRIES;
import static kz.tiyn.platform.jooq.ledger.Tables.SAVINGS_GOALS;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.platform.jooq.ledger.tables.records.SavingsGoalsRecord;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
class GoalStore {

    static final int LIST_LIMIT = 100;

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

    List<GoalView> listForTeen(UUID teenId) {
        Field<BigDecimal> saved =
                DSL.field(dsl.select(DSL.coalesce(DSL.sum(LEDGER_ENTRIES.LOCKED_DELTA), BigDecimal.ZERO))
                        .from(LEDGER_ENTRIES)
                        .where(LEDGER_ENTRIES.REF_ID.eq(SAVINGS_GOALS.ID)));
        return dsl.select(SAVINGS_GOALS.asterisk(), saved)
                .from(SAVINGS_GOALS)
                .where(SAVINGS_GOALS.TEEN_ID.eq(teenId))
                .orderBy(SAVINGS_GOALS.CREATED_AT.desc(), SAVINGS_GOALS.ID)
                .limit(LIST_LIMIT)
                .fetch(r ->
                        new GoalView(toGoal(r.into(SAVINGS_GOALS)), r.get(saved).longValueExact()));
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
