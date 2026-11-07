package kz.tiyn.task.series;

import static kz.tiyn.platform.jooq.task.Tables.TASK_SERIES;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.platform.jooq.task.tables.records.TaskSeriesRecord;
import kz.tiyn.platform.paging.Cursor;
import kz.tiyn.platform.paging.Page;
import kz.tiyn.platform.paging.PageRequest;
import kz.tiyn.platform.time.Instants;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
class SeriesStore {

    private final DSLContext dsl;

    SeriesStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    void insert(TaskSeries series) {
        dsl.insertInto(TASK_SERIES)
                .set(TASK_SERIES.ID, series.id())
                .set(TASK_SERIES.PARENT_ID, series.parentId())
                .set(TASK_SERIES.TEEN_ID, series.teenId())
                .set(TASK_SERIES.KIND, "TASK")
                .set(TASK_SERIES.TITLE, series.title())
                .set(TASK_SERIES.DESCRIPTION, series.description())
                .set(TASK_SERIES.REWARD_AMOUNT, series.rewardAmount())
                .set(TASK_SERIES.PENALTY_AMOUNT, series.penaltyAmount())
                .set(TASK_SERIES.CURRENCY, series.currency())
                .set(TASK_SERIES.RECURRENCE, series.recurrence().name())
                .set(TASK_SERIES.TIMEZONE, series.zone().getId())
                .set(TASK_SERIES.DUE_TIME, series.dueTime())
                .set(TASK_SERIES.STARTS_ON, series.startsOn())
                .execute();
    }

    Optional<TaskSeries> find(UUID id) {
        return dsl.selectFrom(TASK_SERIES)
                .where(TASK_SERIES.ID.eq(id))
                .fetchOptional()
                .map(SeriesStore::toSeries);
    }

    Page<TaskSeries> pageForParent(UUID parentId, UUID teenId, Boolean active, String filterHash, PageRequest request) {
        Condition where = TASK_SERIES.PARENT_ID.eq(parentId);
        if (teenId != null) {
            where = where.and(TASK_SERIES.TEEN_ID.eq(teenId));
        }
        if (active != null) {
            where = where.and(TASK_SERIES.ACTIVE.eq(active));
        }
        var ordered =
                dsl.selectFrom(TASK_SERIES).where(where).orderBy(TASK_SERIES.CREATED_AT.desc(), TASK_SERIES.ID.desc());
        Optional<Cursor> after = request.after(filterHash);
        List<TaskSeriesRecord> fetched = after.isPresent()
                ? ordered.seek(
                                Instants.toUtc(after.get().sortKey()),
                                after.get().id())
                        .limit(request.fetchSize())
                        .fetch()
                : ordered.limit(request.fetchSize()).fetch();
        return Page.of(fetched, request, r -> new Cursor(Instants.fromUtc(r.getCreatedAt()), r.getId(), filterHash))
                .map(SeriesStore::toSeries);
    }

    List<TaskSeries> findActive() {
        return dsl.selectFrom(TASK_SERIES).where(TASK_SERIES.ACTIVE.isTrue()).fetch().stream()
                .map(SeriesStore::toSeries)
                .toList();
    }

    void closeAllFor(UUID parentId, UUID teenId) {
        dsl.update(TASK_SERIES)
                .set(TASK_SERIES.ACTIVE, false)
                .where(TASK_SERIES.PARENT_ID.eq(parentId))
                .and(TASK_SERIES.TEEN_ID.eq(teenId))
                .and(TASK_SERIES.ACTIVE.isTrue())
                .execute();
    }

    void saveActive(TaskSeries series) {
        dsl.update(TASK_SERIES)
                .set(TASK_SERIES.ACTIVE, series.isActive())
                .where(TASK_SERIES.ID.eq(series.id()))
                .execute();
    }

    private static TaskSeries toSeries(TaskSeriesRecord r) {
        return TaskSeries.restore(
                r.getId(),
                r.getParentId(),
                r.getTeenId(),
                r.getTitle(),
                r.getDescription(),
                r.getRewardAmount(),
                r.getPenaltyAmount(),
                r.getCurrency(),
                r.getRecurrence(),
                r.getTimezone(),
                r.getDueTime(),
                r.getStartsOn(),
                r.getActive());
    }
}
