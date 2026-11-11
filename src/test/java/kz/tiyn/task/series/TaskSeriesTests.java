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
                TEEN, " Walk dog ", null, 100, 0, "KZT", recurrence, "Asia/Almaty", LocalTime.of(18, 0), startsOn);
        return TaskSeries.create(UUID.randomUUID(), PARENT, draft, at(START, "08:00"));
    }

    @Test
    void everyOccurrenceCarriesTheSeriesPenalty() {
        var draft = new NewSeries(
                TEEN, "Walk dog", null, 100, 25, "KZT", "DAILY", "Asia/Almaty", LocalTime.of(18, 0), START);
        TaskSeries withPenalty = TaskSeries.create(UUID.randomUUID(), PARENT, draft, at(START, "08:00"));

        var occurrence = withPenalty.dueOccurrence(at(START, "09:00")).orElseThrow();

        assertThat(occurrence.penaltyAmount()).isEqualTo(25);
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
            new NewSeries(TEEN, "t", null, 1, 0, "KZT", "MONTHLY", null, LocalTime.NOON, null),
            new NewSeries(TEEN, "t", null, 1, 0, "KZT", null, null, LocalTime.NOON, null),
            new NewSeries(TEEN, "t", null, 1, 0, "KZT", "DAILY", "Mars/Base", LocalTime.NOON, null),
            new NewSeries(TEEN, "t", null, 1, 0, "KZT", "DAILY", null, null, null),
            new NewSeries(TEEN, "t", null, 1, 0, "KZT", "DAILY", null, LocalTime.NOON, START.minusDays(1))
        }) {
            assertThatThrownBy(() -> TaskSeries.create(UUID.randomUUID(), PARENT, bad, now))
                    .isInstanceOfSatisfying(
                            TaskException.class, e -> assertThat(e.error()).isEqualTo(TaskError.INVALID_SCHEDULE));
        }
    }

    private static void assertFails(Runnable action, TaskError expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.error())
                .isEqualTo(expected));
    }

    @Test
    void aPausedSeriesCreatesNothingAndResumingDoesNotBackfillMissedDays() {
        TaskSeries daily = series("DAILY", START);

        daily.pause(PARENT);
        daily.pause(PARENT);
        assertThat(daily.isActive()).isFalse();
        assertThat(daily.isClosed()).isFalse();
        assertThat(daily.dueOccurrence(at(START.plusDays(1), "09:00"))).isEmpty();

        daily.resume(PARENT);
        daily.resume(PARENT);
        assertThat(daily.isActive()).isTrue();
        assertThat(daily.dueOccurrence(at(START.plusDays(4), "09:00"))
                        .orElseThrow()
                        .date())
                .isEqualTo(START.plusDays(4));
    }

    @Test
    void aClosedSeriesCanNeitherBeResumedPausedNorAmended() {
        TaskSeries daily = series("DAILY", START);
        daily.close(PARENT);

        assertThat(daily.isClosed()).isTrue();
        assertFails(() -> daily.resume(PARENT), TaskError.INVALID_TRANSITION);
        assertFails(() -> daily.pause(PARENT), TaskError.INVALID_TRANSITION);
        assertFails(
                () -> daily.amend(PARENT, new SeriesChanges("New", null, null, null, null, null)),
                TaskError.INVALID_TRANSITION);
        assertThat(daily.isActive()).isFalse();
    }

    @Test
    void onlyTheOwnerCanPauseResumeOrAmend() {
        TaskSeries daily = series("DAILY", START);
        UUID stranger = UUID.randomUUID();

        assertFails(() -> daily.pause(stranger), TaskError.FORBIDDEN);
        assertFails(() -> daily.resume(stranger), TaskError.FORBIDDEN);
        assertFails(
                () -> daily.amend(stranger, new SeriesChanges("New", null, null, null, null, null)),
                TaskError.FORBIDDEN);
        assertThat(daily.title()).isEqualTo("Walk dog");
    }

    @Test
    void amendingChangesOnlyTheGivenFieldsAndShapesFutureOccurrences() {
        TaskSeries daily = series("DAILY", START);

        daily.amend(PARENT, new SeriesChanges(" Feed cat ", null, 250L, null, LocalTime.of(20, 30), null));

        var occurrence = daily.dueOccurrence(at(START.plusDays(1), "09:00")).orElseThrow();
        assertThat(occurrence.title()).isEqualTo("Feed cat");
        assertThat(occurrence.rewardAmount()).isEqualTo(250);
        assertThat(occurrence.penaltyAmount()).isZero();
        assertThat(occurrence.deadlineAt()).isEqualTo(at(START.plusDays(1), "20:30"));
        assertThat(daily.recurrence()).isEqualTo(Recurrence.DAILY);
        assertThat(daily.startsOn()).isEqualTo(START);
    }

    @Test
    void anEmptyAmendmentChangesNothing() {
        TaskSeries daily = series("DAILY", START);

        daily.amend(PARENT, new SeriesChanges(null, null, null, null, null, null));

        assertThat(daily.title()).isEqualTo("Walk dog");
        assertThat(daily.dueTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(daily.zone()).isEqualTo(ALMATY);
    }

    @Test
    void changingTheTimezoneMovesTheDeadlineAndRejectsUnknownZonesWithoutPartialChange() {
        TaskSeries daily = series("DAILY", START);

        assertFails(
                () -> daily.amend(PARENT, new SeriesChanges("Changed", null, null, null, null, "Mars/Base")),
                TaskError.INVALID_SCHEDULE);
        assertThat(daily.title()).isEqualTo("Walk dog");

        daily.amend(PARENT, new SeriesChanges(null, null, null, null, null, "Asia/Tashkent"));

        assertThat(daily.zone()).isEqualTo(ZoneId.of("Asia/Tashkent"));
    }

    @Test
    void aBlankTimezoneFallsBackToTheDefaultLikeCreation() {
        TaskSeries daily = series("DAILY", START);
        daily.amend(PARENT, new SeriesChanges(null, null, null, null, null, "Asia/Tashkent"));

        daily.amend(PARENT, new SeriesChanges(null, null, null, null, null, " "));

        assertThat(daily.zone()).isEqualTo(ALMATY);
    }

    @Test
    void anEmptyDescriptionClearsIt() {
        var draft = new NewSeries(TEEN, "Walk dog", "Twice", 100, 0, "KZT", "DAILY", null, LocalTime.of(18, 0), START);
        TaskSeries withDescription = TaskSeries.create(UUID.randomUUID(), PARENT, draft, at(START, "08:00"));

        withDescription.amend(PARENT, new SeriesChanges(null, "", null, null, null, null));

        assertThat(withDescription.description()).isEmpty();
    }
}
