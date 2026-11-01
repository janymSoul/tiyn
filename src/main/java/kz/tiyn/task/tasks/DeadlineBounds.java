package kz.tiyn.task.tasks;

import java.time.Instant;

record DeadlineBounds(Instant earliest, Instant latest) {

    static final DeadlineBounds NONE = new DeadlineBounds(null, null);
}
