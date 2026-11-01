package kz.tiyn.task.series;

import java.time.Clock;
import java.util.UUID;
import kz.tiyn.identity.FamilyDirectory;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.platform.security.Role;
import kz.tiyn.task.series.dto.CreateSeriesRequest;
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

    SeriesService(
            SeriesStore series,
            TaskAssignment assignment,
            FamilyDirectory family,
            TransactionTemplate tx,
            Clock clock) {
        this.series = series;
        this.assignment = assignment;
        this.family = family;
        this.tx = tx;
        this.clock = clock;
    }

    TaskSeries create(Principal caller, CreateSeriesRequest request) {
        requireParent(caller);
        if (request.teenId() == null) {
            throw new TaskException(TaskError.TEEN_REQUIRED);
        }
        if (!family.areLinked(caller.userId(), request.teenId())) {
            throw new TaskException(TaskError.FORBIDDEN);
        }
        assignment.validateTemplate(request.title(), request.description(), request.rewardAmount(), request.currency());
        var draft = new NewSeries(
                request.teenId(),
                request.title(),
                request.description(),
                request.rewardAmount(),
                request.currency(),
                request.recurrence(),
                request.timezone(),
                request.dueTime(),
                request.startsOn());
        var now = clock.instant();
        TaskSeries created = TaskSeries.create(UUID.randomUUID(), caller.userId(), draft, now);
        tx.executeWithoutResult(status -> {
            series.insert(created);
            created.dueOccurrence(now).ifPresent(assignment::assignOccurrence);
        });
        return created;
    }

    void close(Principal caller, UUID id) {
        requireParent(caller);
        TaskSeries found = series.find(id).orElseThrow(() -> new TaskException(TaskError.NOT_FOUND));
        found.close(caller.userId());
        series.saveActive(found);
    }

    private static void requireParent(Principal caller) {
        if (caller.role() != Role.PARENT) {
            throw new TaskException(TaskError.FORBIDDEN);
        }
    }
}
