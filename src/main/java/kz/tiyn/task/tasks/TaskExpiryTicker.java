package kz.tiyn.task.tasks;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.tiyn.platform.tick.Ticker;
import kz.tiyn.platform.tx.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class TaskExpiryTicker implements Ticker {

    private static final Logger log = LoggerFactory.getLogger(TaskExpiryTicker.class);
    private static final int BATCH = 100;

    private final TaskStore tasks;
    private final TaskService service;

    TaskExpiryTicker(TaskStore tasks, TaskService service) {
        this.tasks = tasks;
        this.service = service;
    }

    @Override
    public void tick(Instant now) {
        List<UUID> batch;
        do {
            batch = tasks.findExpiredIds(now, BATCH);
            int expired = 0;
            for (UUID id : batch) {
                try {
                    if (service.expire(id, now)) {
                        expired++;
                    }
                } catch (ConflictException e) {
                    log.debug("task {} changed while expiring, retrying next tick", id);
                } catch (RuntimeException e) {
                    log.error("task {} could not be expired", id, e);
                }
            }
            if (expired == 0) {
                return;
            }
        } while (batch.size() == BATCH);
    }
}
