package kz.tiyn.task.tasks;

import static kz.tiyn.platform.jooq.task.Tables.TASKS;
import static kz.tiyn.platform.jooq.task.Tables.TASK_PROOFS;
import static kz.tiyn.platform.jooq.task.Tables.TASK_SERIES;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import kz.tiyn.media.Storage;
import kz.tiyn.platform.export.ExportProperties;
import kz.tiyn.platform.export.ExportWriter;
import kz.tiyn.platform.export.KeysetScan;
import kz.tiyn.platform.export.KeysetScan.Position;
import kz.tiyn.platform.jooq.task.tables.records.TaskProofsRecord;
import kz.tiyn.platform.jooq.task.tables.records.TaskSeriesRecord;
import kz.tiyn.platform.jooq.task.tables.records.TasksRecord;
import kz.tiyn.platform.security.Role;
import kz.tiyn.platform.time.Instants;
import kz.tiyn.task.TaskDataReader;
import kz.tiyn.task.tasks.dto.ProofExport;
import kz.tiyn.task.tasks.dto.SeriesExport;
import kz.tiyn.task.tasks.dto.TaskExport;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaskDataReaderImpl implements TaskDataReader {

    private final DSLContext dsl;
    private final Storage storage;
    private final KeysetScan scan;

    TaskDataReaderImpl(DSLContext dsl, Storage storage, ExportProperties properties) {
        this.dsl = dsl;
        this.storage = storage;
        this.scan = new KeysetScan(properties.pageSize());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void writeTo(UUID userId, Role role, ExportWriter out) {
        if (role == Role.TEEN) {
            out.array("tasks", items -> scanTasks(TASKS.TEEN_ID.eq(userId), this::proofsOf, items));
            return;
        }
        out.array("tasks", items -> scanTasks(TASKS.PARENT_ID.eq(userId), page -> Map.of(), items));
        out.array("taskSeries", items -> scanSeries(userId, items));
    }

    private void scanTasks(
            Condition owner,
            Function<List<TasksRecord>, Map<UUID, List<ProofExport>>> proofLoader,
            ExportWriter.Items items) {
        scan.scan(
                (after, limit) -> tasksAfter(owner, after, limit),
                task -> new Position(Instants.fromUtc(task.getCreatedAt()), task.getId()),
                page -> {
                    Map<UUID, List<ProofExport>> proofs = proofLoader.apply(page);
                    page.forEach(task -> items.add(toExport(task, proofs.get(task.getId()))));
                });
    }

    private List<TasksRecord> tasksAfter(Condition owner, Position after, int limit) {
        var ordered = dsl.selectFrom(TASKS).where(owner).orderBy(TASKS.CREATED_AT.desc(), TASKS.ID.desc());
        return after == null
                ? ordered.limit(limit).fetch()
                : ordered.seek(Instants.toUtc(after.sortKey()), after.id())
                        .limit(limit)
                        .fetch();
    }

    private Map<UUID, List<ProofExport>> proofsOf(List<TasksRecord> tasks) {
        Map<UUID, List<ProofExport>> proofs = dsl
                .selectFrom(TASK_PROOFS)
                .where(TASK_PROOFS.TASK_ID.in(
                        tasks.stream().map(TasksRecord::getId).toList()))
                .orderBy(TASK_PROOFS.TASK_ID, TASK_PROOFS.ATTEMPT_NO)
                .fetch()
                .stream()
                .collect(Collectors.groupingBy(
                        TaskProofsRecord::getTaskId,
                        Collectors.mapping(p -> ProofExport.from(p, storage::presignView), Collectors.toList())));
        tasks.forEach(task -> proofs.putIfAbsent(task.getId(), List.of()));
        return proofs;
    }

    private static TaskExport toExport(TasksRecord t, List<ProofExport> proofs) {
        return new TaskExport(
                t.getId(),
                t.getParentId(),
                t.getTeenId(),
                t.getMilestoneId(),
                t.getSeriesId(),
                t.getOccurrenceDate(),
                t.getKind(),
                t.getTitle(),
                t.getDescription(),
                t.getRewardAmount(),
                t.getPenaltyAmount(),
                t.getCurrency(),
                t.getState(),
                Instants.fromUtc(t.getDeadlineAt()),
                Instants.fromUtc(t.getSubmittedAt()),
                Instants.fromUtc(t.getDecidedAt()),
                t.getDecidedBy(),
                t.getDecisionNote(),
                Instants.fromUtc(t.getCreatedAt()),
                proofs);
    }

    private void scanSeries(UUID parentId, ExportWriter.Items items) {
        scan.scan(
                (after, limit) -> seriesAfter(parentId, after, limit),
                series -> new Position(Instants.fromUtc(series.getCreatedAt()), series.getId()),
                page -> page.forEach(series -> items.add(toExport(series))));
    }

    private List<TaskSeriesRecord> seriesAfter(UUID parentId, Position after, int limit) {
        var ordered = dsl.selectFrom(TASK_SERIES)
                .where(TASK_SERIES.PARENT_ID.eq(parentId))
                .orderBy(TASK_SERIES.CREATED_AT.desc(), TASK_SERIES.ID.desc());
        return after == null
                ? ordered.limit(limit).fetch()
                : ordered.seek(Instants.toUtc(after.sortKey()), after.id())
                        .limit(limit)
                        .fetch();
    }

    private static SeriesExport toExport(TaskSeriesRecord s) {
        return new SeriesExport(
                s.getId(),
                s.getTeenId(),
                s.getKind(),
                s.getTitle(),
                s.getDescription(),
                s.getRewardAmount(),
                s.getPenaltyAmount(),
                s.getCurrency(),
                s.getRecurrence(),
                s.getTimezone(),
                s.getDueTime(),
                s.getStartsOn(),
                s.getActive(),
                Instants.fromUtc(s.getCreatedAt()));
    }
}
