package kz.tiyn.task.tasks;

import java.util.UUID;
import kz.tiyn.task.PendingReader;
import org.springframework.stereotype.Service;

@Service
class PendingReaderImpl implements PendingReader {

    private final TaskStore tasks;

    PendingReaderImpl(TaskStore tasks) {
        this.tasks = tasks;
    }

    @Override
    public long pendingReward(UUID teenId) {
        return tasks.pendingReward(teenId);
    }
}
