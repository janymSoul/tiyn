package kz.tiyn.task.tasks;

import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class TaskAssignmentService implements TaskAssignment {

    private final TaskStore tasks;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final TaskProperties properties;
    private final Clock clock;

    TaskAssignmentService(
            TaskStore tasks,
            TransactionTemplate tx,
            ApplicationEventPublisher events,
            TaskProperties properties,
            Clock clock) {
        this.tasks = tasks;
        this.tx = tx;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void validateTemplate(String title, String description, long rewardAmount, String currency) {
        var draft = new NewTask(
                UUID.randomUUID(),
                UUID.randomUUID(),
                TaskKind.TASK,
                title,
                description,
                rewardAmount,
                currency,
                clock.instant().plusSeconds(86_400));
        Task.assign(UUID.randomUUID(), draft, clock.instant(), properties.maxRewardAmount());
    }

    @Override
    public boolean assignOccurrence(Occurrence o) {
        var draft = new NewTask(
                o.parentId(),
                o.teenId(),
                TaskKind.TASK,
                o.title(),
                o.description(),
                o.rewardAmount(),
                o.currency(),
                o.deadlineAt());
        Task task = Task.assignOccurrence(
                UUID.randomUUID(), o.seriesId(), o.date(), draft, clock.instant(), properties.maxRewardAmount());
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (!tasks.insert(task)) {
                return false;
            }
            task.pullEvents().forEach(events::publishEvent);
            return true;
        }));
    }
}
