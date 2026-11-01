package kz.tiyn.task.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import kz.tiyn.task.tasks.error.TaskError;
import kz.tiyn.task.tasks.error.TaskException;
import org.junit.jupiter.api.Test;

class TaskSeriesTests {

    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");
    private static final UUID PARENT = UUID.randomUUID();
    private static final UUID TEEN = UUID.randomUUID();
    private static final LocalDate START = LocalDate.of(2026, 3, 2);

    private static Instant at(LocalDate day, String time) {
        return ZonedDateTime.of(day, LocalTime.parse(time), ALMATY).toInstant();
    }

    private static TaskSeries series(String recurrence, LocalDate startsOn) {
        var draft = new NewSeries(
                TEEN, " Walk dog ", null, 100, "KZT", recurrence, "Asia/Almaty", LocalTime.of(18, 0), startsOn);
        return TaskSeries.create(UUID.randomUUID(), PARENT, draft, at(START, "08:00"));
    }

    @Test
    void dailySeriesHasAnOccurrenceEachDayWithTheDeadlineInItsOwnTimezone() {
        TaskSeries daily = series("daily", START);

        var occurrence = daily.dueOccurrence(at(START.plusDays(3), "09:00")).orElseThrow();

        assertThat(occurrence.date()).isEqualTo(START.plusDays(3));
        assertThat(occurrence.deadlineAt()).isEqualTo(at(START.plusDays(3), "18:00"));
        assertThat(occurrence.title()).isEqualTo("Walk dog");
    }

    @Test
    void anOccurrenceWhoseDueTimeHasPassedIsSkipped() {
        TaskSeries daily = series("DAILY", START);

        assertThat(daily.dueOccurrence(at(START, "18:00"))).isEmpty();
        assertThat(daily.dueOccurrence(at(START, "23:30"))).isEmpty();
    }

    @Test
    void theDayIsDecidedInTheSeriesTimezoneNotUtc() {
        TaskSeries daily = series("DAILY", START);
        Instant justAfterAlmatyMidnight = at(START.plusDays(1), "00:30");

        assertThat(justAfterAlmatyMidnight.atZone(ZoneId.of("UTC")).toLocalDate())
                .isEqualTo(START);
        assertThat(daily.dueOccurrence(justAfterAlmatyMidnight).orElseThrow().date())
                .isEqualTo(START.plusDays(1));
    }

    @Test
    void weeklySeriesOccursOnlyOnTheWeekdayOfItsStart() {
        TaskSeries weekly = series("WEEKLY", START);

        assertThat(weekly.dueOccurrence(at(START.plusDays(3), "09:00"))).isEmpty();
        assertThat(weekly.dueOccurrence(at(START.plusDays(7), "09:00"))).isPresent();
        assertThat(weekly.dueOccurrence(at(START.plusDays(14), "09:00"))).isPresent();
    }

    @Test
    void nothingOccursBeforeTheStartDateOrAfterClosing() {
        TaskSeries future = series("DAILY", START.plusDays(5));
        assertThat(future.dueOccurrence(at(START.plusDays(2), "09:00"))).isEmpty();
        assertThat(future.dueOccurrence(at(START.plusDays(5), "09:00"))).isPresent();

        future.close(PARENT);
        future.close(PARENT);
        assertThat(future.isActive()).isFalse();
        assertThat(future.dueOccurrence(at(START.plusDays(6), "09:00"))).isEmpty();
    }

    @Test
    void onlyTheOwnerCanCloseAndTheScheduleIsValidated() {
        TaskSeries daily = series("DAILY", START);
        assertThatThrownBy(() -> daily.close(UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        TaskException.class, e -> assertThat(e.error()).isEqualTo(TaskError.FORBIDDEN));

        Instant now = at(START, "08:00");
        for (var bad : new NewSeries[] {
            new NewSeries(TEEN, "t", null, 1, "KZT", "MONTHLY", null, LocalTime.NOON, null),
            new NewSeries(TEEN, "t", null, 1, "KZT", null, null, LocalTime.NOON, null),
            new NewSeries(TEEN, "t", null, 1, "KZT", "DAILY", "Mars/Base", LocalTime.NOON, null),
            new NewSeries(TEEN, "t", null, 1, "KZT", "DAILY", null, null, null),
            new NewSeries(TEEN, "t", null, 1, "KZT", "DAILY", null, LocalTime.NOON, START.minusDays(1))
        }) {
            assertThatThrownBy(() -> TaskSeries.create(UUID.randomUUID(), PARENT, bad, now))
                    .isInstanceOfSatisfying(
                            TaskException.class, e -> assertThat(e.error()).isEqualTo(TaskError.INVALID_SCHEDULE));
        }
    }
}
