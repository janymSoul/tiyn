package kz.tiyn.task.series;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import kz.tiyn.identity.FamilyDirectory;
import kz.tiyn.platform.idempotency.IdempotencyContext;
import kz.tiyn.platform.idempotency.IdempotencyKeys;
import kz.tiyn.platform.paging.Cursor;
import kz.tiyn.platform.paging.Page;
import kz.tiyn.platform.paging.PageRequest;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.platform.security.Role;
import kz.tiyn.platform.tx.ConflictRetry;
import kz.tiyn.task.series.dto.CreateSeriesRequest;
import kz.tiyn.task.series.dto.UpdateSeriesRequest;
import kz.tiyn.task.tasks.TaskAssignment;
import kz.tiyn.task.tasks.error.TaskError;
import kz.tiyn.task.tasks.error.TaskException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class SeriesService {

    private final SeriesStore series;
    private final TaskAssignment assignment;
    private final FamilyDirectory family;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final IdempotencyKeys idempotency;

    SeriesService(
            SeriesStore series,
            TaskAssignment assignment,
            FamilyDirectory family,
            TransactionTemplate tx,
            Clock clock,
            IdempotencyKeys idempotency) {
        this.series = series;
        this.assignment = assignment;
        this.family = family;
        this.tx = tx;
        this.clock = clock;
        this.idempotency = idempotency;
    }

    TaskSeries create(Principal caller, IdempotencyContext context, CreateSeriesRequest request) {
        requireParent(caller);
        if (request.teenId() == null) {
            throw new TaskException(TaskError.TEEN_REQUIRED);
        }
        if (!family.areLinked(caller.userId(), request.teenId())) {
            throw new TaskException(TaskError.FORBIDDEN);
        }
        assignment.validateTemplate(
                request.title(),
                request.description(),
                request.rewardAmount(),
                request.penaltyAmount(),
                request.currency());
        var draft = new NewSeries(
                request.teenId(),
                request.title(),
                request.description(),
                request.rewardAmount(),
                request.penaltyAmount(),
                request.currency(),
                request.recurrence(),
                request.timezone(),
                request.dueTime(),
                request.startsOn());
        var now = clock.instant();
        TaskSeries created = TaskSeries.create(UUID.randomUUID(), caller.userId(), draft, now);
        return tx.execute(status -> {
            Optional<UUID> previous =
                    idempotency.claimOrFindPrevious(caller.userId(), context, "series.create", created.id(), request);
            if (previous.isPresent()) {
                return series.find(previous.get()).orElseThrow(() -> new TaskException(TaskError.NOT_FOUND));
            }
            series.insert(created);
            created.dueOccurrence(now).ifPresent(assignment::assignOccurrence);
            return created;
        });
    }

    Page<TaskSeries> list(Principal caller, UUID teenId, Boolean active, PageRequest page) {
        requireParent(caller);
        if (teenId != null && !family.areLinked(caller.userId(), teenId)) {
            throw new TaskException(TaskError.FORBIDDEN);
        }
        return series.pageForParent(
                caller.userId(), teenId, active, Cursor.hashOf("task-series", teenId, active), page);
    }

    TaskSeries get(Principal caller, UUID id) {
        requireParent(caller);
        return series.find(id)
                .filter(found -> found.parentId().equals(caller.userId()))
                .orElseThrow(() -> new TaskException(TaskError.NOT_FOUND));
    }

    TaskSeries update(Principal caller, UUID id, UpdateSeriesRequest request) {
        requireParent(caller);
        return retry(() -> tx.execute(status -> updateOnce(caller, id, request)));
    }

    void close(Principal caller, UUID id) {
        requireParent(caller);
        retry(() -> tx.execute(status -> closeOnce(caller, id)));
    }

    private TaskSeries updateOnce(Principal caller, UUID id, UpdateSeriesRequest request) {
        TaskSeries found = series.find(id).orElseThrow(() -> new TaskException(TaskError.NOT_FOUND));
        if (!family.areLinked(caller.userId(), found.teenId())) {
            throw new TaskException(TaskError.FORBIDDEN);
        }
        var changes = new SeriesChanges(
                request.title(),
                request.description(),
                request.rewardAmount(),
                request.penaltyAmount(),
                request.dueTime(),
                request.timezone());
        found.amend(caller.userId(), changes);
        if (changes.touchesTemplate()) {
            assignment.validateTemplate(
                    found.title(), found.description(), found.rewardAmount(), found.penaltyAmount(), found.currency());
        }
        if (Boolean.FALSE.equals(request.active())) {
            found.pause(caller.userId());
        } else if (Boolean.TRUE.equals(request.active())) {
            found.resume(caller.userId());
        }
        series.saveVersioned(found);
        return found;
    }

    private TaskSeries closeOnce(Principal caller, UUID id) {
        TaskSeries found = series.find(id).orElseThrow(() -> new TaskException(TaskError.NOT_FOUND));
        found.close(caller.userId());
        series.saveVersioned(found);
        return found;
    }

    private static <T> T retry(Supplier<T> work) {
        return ConflictRetry.run(work, () -> new TaskException(TaskError.CONCURRENT_MODIFICATION));
    }

    void closeAfterUnlink(UUID parentId, UUID teenId) {
        tx.executeWithoutResult(status -> {
            if (!family.areLinked(parentId, teenId)) {
                series.closeAllFor(parentId, teenId);
            }
        });
    }

    private static void requireParent(Principal caller) {
        if (caller.role() != Role.PARENT) {
            throw new TaskException(TaskError.FORBIDDEN);
        }
    }
}
