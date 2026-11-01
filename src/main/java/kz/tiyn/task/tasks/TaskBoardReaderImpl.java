package kz.tiyn.task.tasks;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.tiyn.task.BoardTask;
import kz.tiyn.task.TaskBoardReader;
import org.springframework.stereotype.Service;

@Service
class TaskBoardReaderImpl implements TaskBoardReader {

    private final TaskStore tasks;

    TaskBoardReaderImpl(TaskStore tasks) {
        this.tasks = tasks;
    }

    @Override
    public List<BoardTask> board(UUID teenId, Instant since, int limit) {
        List<Task> board = tasks.board(teenId, since, limit);
        Map<UUID, MilestoneProgress> progress = tasks.progressOf(board.stream()
                .filter(t -> t.kind() == TaskKind.MILESTONE)
                .map(Task::id)
                .toList());
        return board.stream().map(t -> toBoardTask(t, progress.get(t.id()))).toList();
    }

    private static BoardTask toBoardTask(Task t, MilestoneProgress progress) {
        return new BoardTask(
                t.id(),
                t.kind().name(),
                t.title(),
                t.description(),
                t.reward().amount(),
                t.penaltyAmount(),
                t.reward().currency(),
                t.state().name(),
                t.deadlineAt(),
                t.milestoneId(),
                progress == null ? null : progress.approvedTasks(),
                progress == null ? null : progress.totalTasks());
    }
}
