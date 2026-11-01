package kz.tiyn.task.series;

import java.time.LocalDate;

enum Recurrence {
    DAILY,
    WEEKLY;

    boolean occursOn(LocalDate startsOn, LocalDate date) {
        return this == DAILY || date.getDayOfWeek() == startsOn.getDayOfWeek();
    }
}
