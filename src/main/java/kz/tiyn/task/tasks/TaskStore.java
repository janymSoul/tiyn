package kz.tiyn.task.tasks;

import static kz.tiyn.platform.jooq.task.Tables.TASKS;
import static kz.tiyn.platform.jooq.task.Tables.TASK_PROOFS;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.platform.jooq.task.tables.records.TasksRecord;
import kz.tiyn.platform.time.Instants;
import kz.tiyn.platform.tx.ConflictException;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
class TaskStore {

    private final DSLContext dsl;
    private final ApplicationEventPublisher events;

    TaskStore(DSLContext dsl, ApplicationEventPublisher events) {
        this.dsl = dsl;
        this.events = events;
    }

    boolean insert(Task task) {
        boolean inserted = dsl.insertInto(TASKS)
                        .set(TASKS.ID, task.id())
                        .set(TASKS.SERIES_ID, task.seriesId())
                        .set(TASKS.OCCURRENCE_DATE, task.occurrenceDate())
                        .set(TASKS.MILESTONE_ID, task.milestoneId())
                        .set(TASKS.PARENT_ID, task.parentId())
                        .set(TASKS.TEEN_ID, task.teenId())
                        .set(TASKS.KIND, task.kind().name())
                        .set(TASKS.TITLE, task.title())
                        .set(TASKS.DESCRIPTION, task.description())
                        .set(TASKS.REWARD_AMOUNT, task.reward().amount())
                        .set(TASKS.PENALTY_AMOUNT, task.penaltyAmount())
                        .set(TASKS.CURRENCY, task.reward().currency())
                        .set(TASKS.STATE, task.state().name())
                        .set(TASKS.DEADLINE_AT, Instants.toUtc(task.deadlineAt()))
                        .onConflictDoNothing()
                        .execute()
                == 1;
        if (inserted) {
            task.pullEvents().forEach(events::publishEvent);
        }
        return inserted;
    }

    Optional<Task> find(UUID id) {
        return dsl.selectFrom(TASKS).where(TASKS.ID.eq(id)).fetchOptional().map(this::toTask);
    }

    Optional<Task> findLocked(UUID id) {
        return dsl.selectFrom(TASKS)
                .where(TASKS.ID.eq(id))
                .forUpdate()
                .fetchOptional()
                .map(this::toTask);
    }

    List<Task> list(UUID parentId, UUID teenId, TaskState state) {
        Condition where = DSL.noCondition();
        if (parentId != null) {
            where = where.and(TASKS.PARENT_ID.eq(parentId));
        }
        if (teenId != null) {
            where = where.and(TASKS.TEEN_ID.eq(teenId));
        }
        if (state != null) {
            where = where.and(TASKS.STATE.eq(state.name()));
        }
        return dsl.selectFrom(TASKS).where(where).orderBy(TASKS.CREATED_AT.desc()).limit(200).fetch().stream()
                .map(this::toTask)
                .toList();
    }

    List<Task> board(UUID teenId, Instant since, int limit) {
        List<String> open = List.of(TaskState.ASSIGNED.name(), TaskState.SUBMITTED.name());
        var isOpen = TASKS.STATE.in(open);
        var settledAt = DSL.coalesce(TASKS.DECIDED_AT, TASKS.UPDATED_AT);
        return dsl.selectFrom(TASKS)
                .where(TASKS.TEEN_ID.eq(teenId))
                .and(isOpen.or(settledAt.ge(Instants.toUtc(since))))
                .orderBy(
                        DSL.when(isOpen, 0).otherwise(1).asc(),
                        DSL.when(isOpen, TASKS.DEADLINE_AT).asc(),
                        settledAt.desc(),
                        TASKS.ID)
                .limit(limit)
                .fetch()
                .map(this::toTask);
    }

    Map<UUID, MilestoneProgress> progressOf(List<UUID> milestoneIds) {
        if (milestoneIds.isEmpty()) {
            return Map.of();
        }
        var total = DSL.count();
        var approved = DSL.count().filterWhere(TASKS.STATE.eq(TaskState.APPROVED.name()));
        return dsl.select(TASKS.MILESTONE_ID, approved, total)
                .from(TASKS)
                .where(TASKS.MILESTONE_ID.in(milestoneIds))
                .and(TASKS.STATE.ne(TaskState.CANCELLED.name()))
                .groupBy(TASKS.MILESTONE_ID)
                .fetchMap(r -> r.value1(), r -> new MilestoneProgress(r.value2(), r.value3()));
    }

    void lock(UUID id) {
        dsl.select(TASKS.ID).from(TASKS).where(TASKS.ID.eq(id)).forUpdate().fetch();
    }

    List<Task> findOpenFor(UUID parentId, UUID teenId) {
        return dsl.selectFrom(TASKS)
                .where(TASKS.PARENT_ID.eq(parentId))
                .and(TASKS.TEEN_ID.eq(teenId))
                .and(TASKS.STATE.in(TaskState.ASSIGNED.name(), TaskState.SUBMITTED.name()))
                .fetch()
                .map(this::toTask);
    }

    Instant latestOpenChildDeadline(UUID milestoneId) {
        return Instants.fromUtc(dsl.select(DSL.max(TASKS.DEADLINE_AT))
                .from(TASKS)
                .where(TASKS.MILESTONE_ID.eq(milestoneId))
                .and(TASKS.STATE.in(TaskState.ASSIGNED.name(), TaskState.SUBMITTED.name()))
                .fetchOne(0, OffsetDateTime.class));
    }

    List<Task> findOpenInMilestone(UUID milestoneId) {
        return dsl.selectFrom(TASKS)
                .where(TASKS.MILESTONE_ID.eq(milestoneId))
                .and(TASKS.STATE.in(TaskState.ASSIGNED.name(), TaskState.SUBMITTED.name()))
                .fetch()
                .map(this::toTask);
    }

    MilestoneProgress progress(UUID milestoneId) {
        var total = DSL.count();
        var approved = DSL.count().filterWhere(TASKS.STATE.eq(TaskState.APPROVED.name()));
        return dsl.select(approved, total)
                .from(TASKS)
                .where(TASKS.MILESTONE_ID.eq(milestoneId))
                .and(TASKS.STATE.ne(TaskState.CANCELLED.name()))
                .fetchOne(r -> new MilestoneProgress(r.value1(), r.value2()));
    }

    List<UUID> findExpiredIds(Instant now, Collection<UUID> excluded, int limit) {
        return dsl.select(TASKS.ID)
                .from(TASKS)
                .where(TASKS.STATE.eq(TaskState.ASSIGNED.name()))
                .and(TASKS.DEADLINE_AT.le(Instants.toUtc(now)))
                .and(TASKS.ID.notIn(excluded))
                .orderBy(TASKS.DEADLINE_AT)
                .limit(limit)
                .fetch(TASKS.ID);
    }

    long pendingReward(UUID teenId) {
        Long sum = dsl.select(DSL.coalesce(DSL.sum(TASKS.REWARD_AMOUNT), 0L).cast(Long.class))
                .from(TASKS)
                .where(TASKS.TEEN_ID.eq(teenId).and(TASKS.STATE.eq(TaskState.SUBMITTED.name())))
                .fetchOne(0, Long.class);
        return sum == null ? 0 : sum;
    }

    boolean save(Task task) {
        List<Object> raised = task.pullEvents();
        if (raised.isEmpty()) {
            return false;
        }
        int updated = dsl.update(TASKS)
                .set(TASKS.STATE, task.state().name())
                .set(TASKS.DEADLINE_AT, Instants.toUtc(task.deadlineAt()))
                .set(TASKS.SUBMITTED_AT, Instants.toUtc(task.submittedAt()))
                .set(TASKS.DECIDED_AT, Instants.toUtc(task.decidedAt()))
                .set(TASKS.DECIDED_BY, task.decidedBy())
                .set(TASKS.DECISION_NOTE, task.decisionNote())
                .set(TASKS.VERSION, task.version() + 1)
                .where(TASKS.ID.eq(task.id()).and(TASKS.VERSION.eq(task.version())))
                .execute();
        if (updated == 0) {
            throw new ConflictException(new IllegalStateException("stale task " + task.id()));
        }
        try {
            for (Proof proof : task.unsavedProofs()) {
                dsl.insertInto(TASK_PROOFS)
                        .set(TASK_PROOFS.ID, proof.id())
                        .set(TASK_PROOFS.TASK_ID, task.id())
                        .set(TASK_PROOFS.ATTEMPT_NO, proof.attemptNo())
                        .set(TASK_PROOFS.OBJECT_KEY, proof.objectKey())
                        .set(TASK_PROOFS.TEXT_BODY, proof.textBody())
                        .execute();
            }
        } catch (DuplicateKeyException e) {
            throw new ConflictException(e);
        }
        raised.forEach(events::publishEvent);
        return true;
    }

    private Task toTask(TasksRecord r) {
        var snapshot = new TaskSnapshot(
                r.getId(),
                r.getSeriesId(),
                r.getOccurrenceDate(),
                r.getMilestoneId(),
                r.getParentId(),
                r.getTeenId(),
                TaskKind.valueOf(r.getKind()),
                r.getTitle(),
                r.getDescription(),
                r.getRewardAmount(),
                r.getPenaltyAmount(),
                r.getCurrency(),
                TaskState.valueOf(r.getState()),
                Instants.fromUtc(r.getDeadlineAt()),
                r.getVersion(),
                Instants.fromUtc(r.getSubmittedAt()),
                Instants.fromUtc(r.getDecidedAt()),
                r.getDecidedBy(),
                r.getDecisionNote());
        List<Proof> proofs = dsl.selectFrom(TASK_PROOFS)
                .where(TASK_PROOFS.TASK_ID.eq(r.getId()))
                .orderBy(TASK_PROOFS.ATTEMPT_NO)
                .fetch()
                .map(p -> new Proof(
                        p.getId(),
                        p.getAttemptNo(),
                        p.getObjectKey(),
                        p.getTextBody(),
                        p.getCreatedAt().toInstant()));
        return Task.restore(snapshot, proofs);
    }
}
