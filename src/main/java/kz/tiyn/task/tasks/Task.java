package kz.tiyn.task.tasks;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kz.tiyn.platform.event.EventLog;
import kz.tiyn.task.TaskApproved;
import kz.tiyn.task.TaskAssigned;
import kz.tiyn.task.TaskCancelled;
import kz.tiyn.task.TaskExpired;
import kz.tiyn.task.TaskRedoRequested;
import kz.tiyn.task.TaskRejected;
import kz.tiyn.task.TaskSubmitted;
import kz.tiyn.task.tasks.error.TaskError;
import kz.tiyn.task.tasks.error.TaskException;

final class Task {

    static final int MAX_TITLE = 200;
    static final int MAX_NOTE = 500;

    private final UUID id;
    private final UUID seriesId;
    private final LocalDate occurrenceDate;
    private final UUID milestoneId;
    private final UUID parentId;
    private final UUID teenId;
    private final TaskKind kind;
    private final String title;
    private final String description;
    private final Money reward;
    private Instant deadlineAt;
    private TaskState state;
    private final int version;
    private Instant submittedAt;
    private Instant decidedAt;
    private UUID decidedBy;
    private String decisionNote;
    private final List<Proof> proofs;
    private final int persistedProofs;
    private final EventLog events = new EventLog();

    private Task(TaskSnapshot s, List<Proof> proofs) {
        this.id = s.id();
        this.seriesId = s.seriesId();
        this.occurrenceDate = s.occurrenceDate();
        this.milestoneId = s.milestoneId();
        this.parentId = s.parentId();
        this.teenId = s.teenId();
        this.kind = s.kind();
        this.title = s.title();
        this.description = s.description();
        this.reward = new Money(s.rewardAmount(), s.currency());
        this.deadlineAt = s.deadlineAt();
        this.state = s.state();
        this.version = s.version();
        this.submittedAt = s.submittedAt();
        this.decidedAt = s.decidedAt();
        this.decidedBy = s.decidedBy();
        this.decisionNote = s.decisionNote();
        this.proofs = new ArrayList<>(proofs);
        this.persistedProofs = proofs.size();
    }

    static Task assign(UUID id, NewTask draft, Instant now, long maxRewardAmount) {
        return create(id, null, draft, now, maxRewardAmount);
    }

    static Task assignInMilestone(UUID id, Task milestone, NewTask draft, Instant now, long maxRewardAmount) {
        if (draft.deadlineAt() != null && draft.deadlineAt().isAfter(milestone.deadlineAt)) {
            throw new TaskException(TaskError.INVALID_MILESTONE);
        }
        boolean belongsToThisFamily =
                milestone.parentId.equals(draft.parentId()) && milestone.teenId.equals(draft.teenId());
        boolean acceptsTasks = milestone.kind == TaskKind.MILESTONE && milestone.state == TaskState.ASSIGNED;
        if (!belongsToThisFamily || !acceptsTasks || draft.kind() == TaskKind.MILESTONE) {
            throw new TaskException(TaskError.INVALID_MILESTONE);
        }
        return create(id, milestone.id, draft, now, maxRewardAmount);
    }

    private static Task create(UUID id, UUID milestoneId, NewTask draft, Instant now, long maxRewardAmount) {
        String title = draft.title() == null ? "" : draft.title().trim();
        if (title.isEmpty() || title.length() > MAX_TITLE) {
            throw new TaskException(TaskError.INVALID_TITLE);
        }
        Money reward = Money.reward(draft.rewardAmount(), draft.currency(), maxRewardAmount);
        if (draft.deadlineAt() == null || !draft.deadlineAt().isAfter(now)) {
            throw new TaskException(TaskError.INVALID_DEADLINE);
        }
        String description =
                draft.description() == null ? "" : draft.description().trim();
        TaskKind kind = draft.kind() == null ? TaskKind.TASK : draft.kind();
        var snapshot = new TaskSnapshot(
                id,
                null,
                null,
                milestoneId,
                draft.parentId(),
                draft.teenId(),
                kind,
                title,
                description,
                reward.amount(),
                reward.currency(),
                TaskState.ASSIGNED,
                draft.deadlineAt(),
                0,
                null,
                null,
                null,
                null);
        Task task = new Task(snapshot, List.of());
        task.events.record(new TaskAssigned(id, draft.teenId(), kind.name(), reward.amount(), reward.currency()));
        return task;
    }

    static Task restore(TaskSnapshot snapshot, List<Proof> proofs) {
        return new Task(snapshot, proofs);
    }

    void submit(UUID byTeenId, UUID proofId, String objectKey, String textBody, Instant now) {
        if (!teenId.equals(byTeenId)) {
            throw new TaskException(TaskError.FORBIDDEN);
        }
        requireState(TaskState.ASSIGNED);
        if (!now.isBefore(deadlineAt)) {
            throw new TaskException(TaskError.DEADLINE_PASSED);
        }
        proofs.add(Proof.submitted(proofId, proofs.size() + 1, objectKey, textBody, now));
        state = TaskState.SUBMITTED;
        submittedAt = now;
        events.record(new TaskSubmitted(id, parentId, teenId));
    }

    void approve(UUID byParentId, Instant now) {
        if (state == TaskState.APPROVED && byParentId.equals(decidedBy)) {
            return;
        }
        requireState(TaskState.SUBMITTED);
        decide(TaskState.APPROVED, byParentId, null, now);
        events.record(new TaskApproved(id, teenId, reward.amount(), reward.currency()));
    }

    void reject(UUID byParentId, String note, Instant now) {
        if (state == TaskState.REJECTED && byParentId.equals(decidedBy)) {
            return;
        }
        requireState(TaskState.SUBMITTED);
        String cleaned = cleanNote(note);
        decide(TaskState.REJECTED, byParentId, cleaned, now);
        events.record(new TaskRejected(id, teenId, cleaned));
    }

    void requestRedo(UUID byParentId, String note, Instant newDeadlineAt, Instant latestDeadlineAt, Instant now) {
        requireState(TaskState.SUBMITTED);
        String cleaned = cleanNote(note);
        extendDeadline(newDeadlineAt, latestDeadlineAt, now);
        decide(TaskState.ASSIGNED, byParentId, cleaned, now);
        events.record(new TaskRedoRequested(id, teenId, cleaned));
    }

    void expire(Instant now) {
        if (state != TaskState.ASSIGNED || now.isBefore(deadlineAt)) {
            return;
        }
        state = TaskState.EXPIRED;
        events.record(new TaskExpired(id, teenId));
    }

    void cancel(UUID byParentId, Instant now) {
        if (state == TaskState.CANCELLED) {
            return;
        }
        if (state != TaskState.ASSIGNED && state != TaskState.SUBMITTED) {
            throw new TaskException(TaskError.INVALID_TRANSITION);
        }
        decide(TaskState.CANCELLED, byParentId, null, now);
        events.record(new TaskCancelled(id, teenId));
    }

    List<Object> pullEvents() {
        return events.drain();
    }

    int currentAttempt() {
        return state == TaskState.ASSIGNED ? proofs.size() + 1 : proofs.size();
    }

    List<Proof> unsavedProofs() {
        return proofs.subList(persistedProofs, proofs.size());
    }

    private void extendDeadline(Instant newDeadlineAt, Instant latestDeadlineAt, Instant now) {
        if (newDeadlineAt == null) {
            if (!now.isBefore(deadlineAt)) {
                throw new TaskException(TaskError.INVALID_DEADLINE);
            }
            return;
        }
        boolean beyondMilestone = latestDeadlineAt != null && newDeadlineAt.isAfter(latestDeadlineAt);
        if (!newDeadlineAt.isAfter(now) || beyondMilestone) {
            throw new TaskException(TaskError.INVALID_DEADLINE);
        }
        deadlineAt = newDeadlineAt;
    }

    private void decide(TaskState to, UUID by, String note, Instant now) {
        state = to;
        decidedAt = now;
        decidedBy = by;
        decisionNote = note;
    }

    private void requireState(TaskState expected) {
        if (state != expected) {
            throw new TaskException(TaskError.INVALID_TRANSITION);
        }
    }

    private static String cleanNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.length() > MAX_NOTE) {
            throw new TaskException(TaskError.INVALID_NOTE);
        }
        return trimmed;
    }

    UUID id() {
        return id;
    }

    UUID seriesId() {
        return seriesId;
    }

    LocalDate occurrenceDate() {
        return occurrenceDate;
    }

    UUID milestoneId() {
        return milestoneId;
    }

    UUID parentId() {
        return parentId;
    }

    UUID teenId() {
        return teenId;
    }

    TaskKind kind() {
        return kind;
    }

    String title() {
        return title;
    }

    String description() {
        return description;
    }

    Money reward() {
        return reward;
    }

    Instant deadlineAt() {
        return deadlineAt;
    }

    TaskState state() {
        return state;
    }

    int version() {
        return version;
    }

    Instant submittedAt() {
        return submittedAt;
    }

    Instant decidedAt() {
        return decidedAt;
    }

    UUID decidedBy() {
        return decidedBy;
    }

    String decisionNote() {
        return decisionNote;
    }

    List<Proof> proofs() {
        return List.copyOf(proofs);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Task task && id.equals(task.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
