package kz.tiyn.task;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TaskBoardReader {

    List<BoardTask> board(UUID teenId, Instant since, int limit);
}
