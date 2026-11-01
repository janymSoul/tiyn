package kz.tiyn.task.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.tiyn.task.TaskApproved;
import kz.tiyn.task.TaskAssigned;
import kz.tiyn.task.TaskCancelled;
import kz.tiyn.task.TaskRedoRequested;
import kz.tiyn.task.TaskRejected;
import kz.tiyn.task.TaskSubmitted;
import kz.tiyn.task.tasks.error.TaskError;
import kz.tiyn.task.tasks.error.TaskException;
import org.junit.jupiter.api.Test;

class TaskTests {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final UUID PARENT = UUID.randomUUID();
    private static final UUID TEEN = UUID.randomUUID();
    private static final long MAX = 10_000_000;

    private Task assigned() {
        Task task = Task.assign(UUID.randomUUID(), draft(500_00, "KZT", NOW.plusSeconds(3600)), NOW, MAX);
        task.pullEvents();
        return task;
    }

    private Task submitted() {
        Task task = assigned();
        task.submit(TEEN, UUID.randomUUID(), null, "done", NOW);
        task.pullEvents();
        return task;
    }

    private static NewTask draft(long reward, String currency, Instant deadline) {
        return new NewTask(PARENT, TEEN, TaskKind.TASK, " Wash dishes ", null, reward, currency, deadline);
    }

    private static void assertError(Runnable action, TaskError expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.error())
                .isEqualTo(expected));
    }

    @Test
    void assignTrimsTitleAndRecordsTaskAssigned() {
        Task task = Task.assign(UUID.randomUUID(), draft(500_00, "KZT", NOW.plusSeconds(60)), NOW, MAX);

        assertThat(task.title()).isEqualTo("Wash dishes");
        assertThat(task.state()).isEqualTo(TaskState.ASSIGNED);
        assertThat(task.pullEvents()).singleElement().isInstanceOf(TaskAssigned.class);
        assertThat(task.pullEvents()).isEmpty();
    }

    @Test
    void assignRejectsBadInput() {
        UUID id = UUID.randomUUID();
        Instant later = NOW.plusSeconds(60);
        assertError(() -> Task.assign(id, draft(0, "KZT", later), NOW, MAX), TaskError.INVALID_REWARD);
        assertError(() -> Task.assign(id, draft(MAX + 1, "KZT", later), NOW, MAX), TaskError.INVALID_REWARD);
        assertError(() -> Task.assign(id, draft(100, "USD", later), NOW, MAX), TaskError.INVALID_REWARD);
        assertError(() -> Task.assign(id, draft(100, "KZT", NOW), NOW, MAX), TaskError.INVALID_DEADLINE);
        assertError(() -> Task.assign(id, draft(100, "KZT", null), NOW, MAX), TaskError.INVALID_DEADLINE);
        var blank = new NewTask(PARENT, TEEN, TaskKind.TASK, "  ", null, 100, "KZT", later);
        assertError(() -> Task.assign(id, blank, NOW, MAX), TaskError.INVALID_TITLE);
    }

    @Test
    void submitMovesToSubmittedAndRecordsAProof() {
        Task task = assigned();

        task.submit(TEEN, UUID.randomUUID(), "proofs/x", null, NOW);

        assertThat(task.state()).isEqualTo(TaskState.SUBMITTED);
        assertThat(task.proofs()).singleElement().satisfies(p -> assertThat(p.attemptNo())
                .isEqualTo(1));
        assertThat(task.pullEvents()).singleElement().isInstanceOf(TaskSubmitted.class);
    }

    @Test
    void submitRequiresTheOwnerAnOpenDeadlineAndExactlyOneKindOfProof() {
        assertError(() -> assigned().submit(UUID.randomUUID(), UUID.randomUUID(), null, "x", NOW), TaskError.FORBIDDEN);
        assertError(
                () -> assigned().submit(TEEN, UUID.randomUUID(), null, "x", NOW.plusSeconds(3600)),
                TaskError.DEADLINE_PASSED);
        assertError(() -> assigned().submit(TEEN, UUID.randomUUID(), null, null, NOW), TaskError.UNSUPPORTED_PROOF);
        assertError(() -> assigned().submit(TEEN, UUID.randomUUID(), null, "  ", NOW), TaskError.UNSUPPORTED_PROOF);
        assertError(() -> assigned().submit(TEEN, UUID.randomUUID(), "k", "text", NOW), TaskError.UNSUPPORTED_PROOF);
        assertError(() -> submitted().submit(TEEN, UUID.randomUUID(), null, "x", NOW), TaskError.INVALID_TRANSITION);
    }

    @Test
    void approveRecordsTaskApprovedAndRepeatingBySameParentIsANoOp() {
        Task task = submitted();

        task.approve(PARENT, NOW);
        task.approve(PARENT, NOW.plusSeconds(5));

        assertThat(task.state()).isEqualTo(TaskState.APPROVED);
        assertThat(task.decidedAt()).isEqualTo(NOW);
        assertThat(task.pullEvents()).singleElement().isInstanceOf(TaskApproved.class);
    }

    @Test
    void rejectAndRedoCarryTheNote() {
        Task rejected = submitted();
        rejected.reject(PARENT, " blurry ", NOW);
        assertThat(rejected.state()).isEqualTo(TaskState.REJECTED);
        assertThat(rejected.decisionNote()).isEqualTo("blurry");
        assertThat(rejected.pullEvents()).singleElement().isInstanceOf(TaskRejected.class);

        Task redo = submitted();
        redo.requestRedo(PARENT, "again", NOW);
        assertThat(redo.state()).isEqualTo(TaskState.ASSIGNED);
        assertThat(redo.pullEvents()).singleElement().isInstanceOf(TaskRedoRequested.class);
        redo.submit(TEEN, UUID.randomUUID(), null, "second try", NOW);
        assertThat(redo.proofs()).extracting(Proof::attemptNo).containsExactly(1, 2);
    }

    @Test
    void decisionsAreOnlyPossibleFromSubmittedAndConflictingDecisionsFail() {
        assertError(() -> assigned().approve(PARENT, NOW), TaskError.INVALID_TRANSITION);
        assertError(() -> assigned().reject(PARENT, null, NOW), TaskError.INVALID_TRANSITION);
        assertError(() -> assigned().requestRedo(PARENT, null, NOW), TaskError.INVALID_TRANSITION);
        Task approved = submitted();
        approved.approve(PARENT, NOW);
        assertError(() -> approved.reject(PARENT, null, NOW), TaskError.INVALID_TRANSITION);
        assertError(() -> approved.approve(UUID.randomUUID(), NOW), TaskError.INVALID_TRANSITION);
        assertError(() -> submitted().reject(PARENT, "x".repeat(501), NOW), TaskError.INVALID_NOTE);
    }

    @Test
    void cancelWorksFromOpenStatesIsIdempotentAndFailsAfterApproval() {
        Task fromAssigned = assigned();
        fromAssigned.cancel(PARENT, NOW);
        fromAssigned.cancel(PARENT, NOW);
        assertThat(fromAssigned.state()).isEqualTo(TaskState.CANCELLED);
        assertThat(fromAssigned.pullEvents()).singleElement().isInstanceOf(TaskCancelled.class);

        Task fromSubmitted = submitted();
        fromSubmitted.cancel(PARENT, NOW);
        assertThat(fromSubmitted.state()).isEqualTo(TaskState.CANCELLED);

        Task approved = submitted();
        approved.approve(PARENT, NOW);
        assertError(() -> approved.cancel(PARENT, NOW), TaskError.INVALID_TRANSITION);
    }

    @Test
    void unsavedProofsExcludeThosePersisted() {
        Task task = submitted();
        Task restored = Task.restore(
                new TaskSnapshot(
                        task.id(),
                        null,
                        null,
                        null,
                        PARENT,
                        TEEN,
                        TaskKind.TASK,
                        "t",
                        "",
                        1,
                        "KZT",
                        TaskState.ASSIGNED,
                        NOW.plusSeconds(60),
                        1,
                        null,
                        null,
                        null,
                        null),
                List.copyOf(task.proofs()));
        restored.submit(TEEN, UUID.randomUUID(), null, "again", NOW);

        assertThat(restored.unsavedProofs()).singleElement().satisfies(p -> assertThat(p.attemptNo())
                .isEqualTo(2));
    }
}
