package kz.tiyn.task.tasks;

import java.util.List;
import java.util.UUID;
import kz.tiyn.task.ReviewQueue;
import kz.tiyn.task.ReviewQueueReader;
import kz.tiyn.task.ReviewTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReviewQueueReaderImpl implements ReviewQueueReader {

    private final TaskStore tasks;

    ReviewQueueReaderImpl(TaskStore tasks) {
        this.tasks = tasks;
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewQueue reviewQueue(UUID parentId, UUID teenId, int limit) {
        List<ReviewTask> submitted = tasks.submittedFor(parentId, teenId, limit).stream()
                .map(t -> new ReviewTask(
                        t.id(), t.title(), t.reward().amount(), t.reward().currency(), t.submittedAt()))
                .toList();
        return new ReviewQueue(tasks.countSubmittedFor(parentId, teenId), submitted);
    }
}
