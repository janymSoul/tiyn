package kz.tiyn.task.tasks;

import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import kz.tiyn.identity.FamilyDirectory;
import kz.tiyn.media.InvalidMediaException;
import kz.tiyn.media.Storage;
import kz.tiyn.platform.event.DomainEvents;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.platform.security.Role;
import kz.tiyn.platform.tx.ConflictRetry;
import kz.tiyn.task.tasks.dto.CreateTaskRequest;
import kz.tiyn.task.tasks.dto.ProofResponse;
import kz.tiyn.task.tasks.error.TaskError;
import kz.tiyn.task.tasks.error.TaskException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class TaskService {

    private final TaskStore tasks;
    private final IdempotencyStore keys;
    private final FamilyDirectory family;
    private final Storage storage;
    private final TransactionTemplate tx;
    private final DomainEvents events;
    private final TaskProperties properties;
    private final Clock clock;

    TaskService(
            TaskStore tasks,
            IdempotencyStore keys,
            FamilyDirectory family,
            Storage storage,
            TransactionTemplate tx,
            DomainEvents events,
            TaskProperties properties,
            Clock clock) {
        this.tasks = tasks;
        this.keys = keys;
        this.family = family;
        this.storage = storage;
        this.tx = tx;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    Task create(Principal caller, CreateTaskRequest request) {
        requireRole(caller, Role.PARENT);
        if (request.teenId() == null) {
            throw new TaskException(TaskError.TEEN_REQUIRED);
        }
        requireLinked(caller.userId(), request.teenId());
        TaskKind kind = parseKind(request.kind());
        var draft = new NewTask(
                caller.userId(),
                request.teenId(),
                kind,
                request.title(),
                request.description(),
                request.rewardAmount(),
                request.currency(),
                request.deadlineAt());
        Task task = Task.assign(UUID.randomUUID(), draft, clock.instant(), properties.maxRewardAmount());
        tx.executeWithoutResult(status -> {
            tasks.insert(task);
            events.publishAll(task.pullEvents());
        });
        return task;
    }

    Task get(Principal caller, UUID id) {
        return authorised(caller, id);
    }

    List<Task> list(Principal caller, UUID teenId, TaskState state) {
        if (caller.role() == Role.TEEN) {
            return tasks.list(null, caller.userId(), state);
        }
        return tasks.list(caller.userId(), teenId, state).stream()
                .filter(t -> family.areLinked(t.parentId(), t.teenId()))
                .toList();
    }

    Task submitText(Principal caller, UUID id, String idempotencyKey, String text) {
        requireKey(idempotencyKey);
        return submit(caller, id, idempotencyKey, null, text);
    }

    Task submitImage(Principal caller, UUID id, String idempotencyKey, String contentType, InputStream body) {
        requireKey(idempotencyKey);
        requireRole(caller, Role.TEEN);
        Task current = authorised(caller, id);
        String objectKey;
        try {
            objectKey = storage.put(id.toString(), idempotencyKey, contentType, body);
        } catch (InvalidMediaException e) {
            throw new TaskException(TaskError.UNSUPPORTED_PROOF);
        }
        return submit(caller, current.id(), idempotencyKey, objectKey, null);
    }

    Task approve(Principal caller, UUID id) {
        return decide(caller, id, (t, now) -> t.approve(caller.userId(), now));
    }

    Task reject(Principal caller, UUID id, String note) {
        return decide(caller, id, (t, now) -> t.reject(caller.userId(), note, now));
    }

    Task requestRedo(Principal caller, UUID id, String note) {
        return decide(caller, id, (t, now) -> t.requestRedo(caller.userId(), note, now));
    }

    Task cancel(Principal caller, UUID id) {
        return decide(caller, id, (t, now) -> t.cancel(caller.userId(), now));
    }

    List<ProofResponse> proofs(Principal caller, UUID id) {
        requireRole(caller, Role.PARENT);
        Task task = authorised(caller, id);
        return task.proofs().stream()
                .map(p -> new ProofResponse(
                        p.attemptNo(),
                        p.objectKey() == null ? null : view(p.objectKey()).toString(),
                        p.textBody(),
                        p.createdAt()))
                .toList();
    }

    long pendingReward(UUID teenId) {
        return tasks.pendingReward(teenId);
    }

    private Task submit(Principal caller, UUID id, String idempotencyKey, String objectKey, String text) {
        requireRole(caller, Role.TEEN);
        String claimKey = id + ":" + idempotencyKey;
        return retry(() -> tx.execute(status -> {
            Task task = authorised(caller, id);
            if (!keys.claim(claimKey)) {
                return task;
            }
            task.submit(caller.userId(), UUID.randomUUID(), objectKey, text, clock.instant());
            tasks.update(task);
            events.publishAll(task.pullEvents());
            return task;
        }));
    }

    private Task decide(Principal caller, UUID id, Decision decision) {
        requireRole(caller, Role.PARENT);
        return retry(() -> tx.execute(status -> {
            Task task = authorised(caller, id);
            decision.apply(task, clock.instant());
            List<Object> raised = task.pullEvents();
            if (!raised.isEmpty()) {
                tasks.update(task);
                events.publishAll(raised);
            }
            return task;
        }));
    }

    private Task authorised(Principal caller, UUID id) {
        Task task = tasks.find(id).orElseThrow(() -> new TaskException(TaskError.NOT_FOUND));
        if (caller.role() == Role.TEEN) {
            if (!task.teenId().equals(caller.userId())) {
                throw new TaskException(TaskError.FORBIDDEN);
            }
        } else if (!task.parentId().equals(caller.userId()) || !family.areLinked(task.parentId(), task.teenId())) {
            throw new TaskException(TaskError.FORBIDDEN);
        }
        return task;
    }

    private void requireLinked(UUID parentId, UUID teenId) {
        if (!family.areLinked(parentId, teenId)) {
            throw new TaskException(TaskError.FORBIDDEN);
        }
    }

    private static void requireRole(Principal caller, Role role) {
        if (caller.role() != role) {
            throw new TaskException(TaskError.FORBIDDEN);
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new TaskException(TaskError.INVALID_IDEMPOTENCY_KEY);
        }
    }

    private static TaskKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return TaskKind.TASK;
        }
        try {
            return TaskKind.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new TaskException(TaskError.INVALID_MILESTONE);
        }
    }

    private URI view(String objectKey) {
        return storage.presignView(objectKey);
    }

    private static Task retry(Supplier<Task> work) {
        return ConflictRetry.run(work, () -> new TaskException(TaskError.CONCURRENT_MODIFICATION));
    }

    @FunctionalInterface
    private interface Decision {
        void apply(Task task, java.time.Instant now);
    }
}
