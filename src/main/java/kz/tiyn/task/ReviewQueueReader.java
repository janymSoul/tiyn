package kz.tiyn.task;

import java.util.UUID;

public interface ReviewQueueReader {

    ReviewQueue reviewQueue(UUID parentId, UUID teenId, int limit);
}
