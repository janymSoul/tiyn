package kz.tiyn.task;

import java.util.UUID;

public interface PendingReader {

    long pendingReward(UUID teenId);
}
