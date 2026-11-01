package kz.tiyn.task.series;

import java.time.Instant;
import kz.tiyn.identity.FamilyDirectory;
import kz.tiyn.platform.tick.Ticker;
import kz.tiyn.task.tasks.TaskAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class SeriesTicker implements Ticker {

    private static final Logger log = LoggerFactory.getLogger(SeriesTicker.class);

    private final SeriesStore series;
    private final TaskAssignment assignment;
    private final FamilyDirectory family;

    SeriesTicker(SeriesStore series, TaskAssignment assignment, FamilyDirectory family) {
        this.series = series;
        this.assignment = assignment;
        this.family = family;
    }

    @Override
    public void tick(Instant now) {
        for (TaskSeries active : series.findActive()) {
            try {
                if (!family.areLinked(active.parentId(), active.teenId())) {
                    continue;
                }
                active.dueOccurrence(now).ifPresent(assignment::assignOccurrence);
            } catch (RuntimeException e) {
                log.error("series {} could not be materialised", active.id(), e);
            }
        }
    }
}
