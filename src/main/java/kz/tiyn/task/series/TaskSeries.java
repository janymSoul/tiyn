package kz.tiyn.task.series;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.task.tasks.Occurrence;
import kz.tiyn.task.tasks.error.TaskError;
import kz.tiyn.task.tasks.error.TaskException;

final class TaskSeries {

    static final String DEFAULT_TIMEZONE = "Asia/Almaty";

    private final UUID id;
    private final UUID parentId;
    private final UUID teenId;
    private final String title;
    private final String description;
    private final long rewardAmount;
    private final long penaltyAmount;
    private final String currency;
    private final Recurrence recurrence;
    private final ZoneId zone;
    private final LocalTime dueTime;
    private final LocalDate startsOn;
    private boolean active;

    private TaskSeries(
            UUID id,
            UUID parentId,
            UUID teenId,
            String title,
            String description,
            long rewardAmount,
            long penaltyAmount,
            String currency,
            Recurrence recurrence,
            ZoneId zone,
            LocalTime dueTime,
            LocalDate startsOn,
            boolean active) {
        this.id = id;
        this.parentId = parentId;
        this.teenId = teenId;
        this.title = title;
        this.description = description;
        this.rewardAmount = rewardAmount;
        this.penaltyAmount = penaltyAmount;
        this.currency = currency;
        this.recurrence = recurrence;
        this.zone = zone;
        this.dueTime = dueTime;
        this.startsOn = startsOn;
        this.active = active;
    }

    static TaskSeries create(UUID id, UUID parentId, NewSeries draft, Instant now) {
        Recurrence recurrence = parseRecurrence(draft.recurrence());
        ZoneId zone = parseZone(draft.timezone());
        if (draft.dueTime() == null) {
            throw new TaskException(TaskError.INVALID_SCHEDULE);
        }
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate startsOn = draft.startsOn() == null ? today : draft.startsOn();
        if (startsOn.isBefore(today)) {
            throw new TaskException(TaskError.INVALID_SCHEDULE);
        }
        String description =
                draft.description() == null ? "" : draft.description().trim();
        return new TaskSeries(
                id,
                parentId,
                draft.teenId(),
                draft.title().trim(),
                description,
                draft.rewardAmount(),
                draft.penaltyAmount(),
                draft.currency(),
                recurrence,
                zone,
                draft.dueTime(),
                startsOn,
                true);
    }

    static TaskSeries restore(
            UUID id,
            UUID parentId,
            UUID teenId,
            String title,
            String description,
            long rewardAmount,
            long penaltyAmount,
            String currency,
            String recurrence,
            String timezone,
            LocalTime dueTime,
            LocalDate startsOn,
            boolean active) {
        return new TaskSeries(
                id,
                parentId,
                teenId,
                title,
                description,
                rewardAmount,
                penaltyAmount,
                currency,
                Recurrence.valueOf(recurrence),
                ZoneId.of(timezone),
                dueTime,
                startsOn,
                active);
    }

    Optional<Occurrence> dueOccurrence(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (!active || today.isBefore(startsOn) || !recurrence.occursOn(startsOn, today)) {
            return Optional.empty();
        }
        Instant deadline = ZonedDateTime.of(today, dueTime, zone).toInstant();
        if (!deadline.isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(new Occurrence(
                id, today, parentId, teenId, title, description, rewardAmount, penaltyAmount, currency, deadline));
    }

    void close(UUID byParentId) {
        if (!parentId.equals(byParentId)) {
            throw new TaskException(TaskError.FORBIDDEN);
        }
        active = false;
    }

    private static Recurrence parseRecurrence(String raw) {
        try {
            return Recurrence.valueOf(raw == null ? "" : raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new TaskException(TaskError.INVALID_SCHEDULE);
        }
    }

    private static ZoneId parseZone(String raw) {
        try {
            return ZoneId.of(raw == null || raw.isBlank() ? DEFAULT_TIMEZONE : raw);
        } catch (DateTimeException e) {
            throw new TaskException(TaskError.INVALID_SCHEDULE);
        }
    }

    UUID id() {
        return id;
    }

    UUID parentId() {
        return parentId;
    }

    UUID teenId() {
        return teenId;
    }

    String title() {
        return title;
    }

    String description() {
        return description;
    }

    long rewardAmount() {
        return rewardAmount;
    }

    long penaltyAmount() {
        return penaltyAmount;
    }

    String currency() {
        return currency;
    }

    Recurrence recurrence() {
        return recurrence;
    }

    ZoneId zone() {
        return zone;
    }

    LocalTime dueTime() {
        return dueTime;
    }

    LocalDate startsOn() {
        return startsOn;
    }

    boolean isActive() {
        return active;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TaskSeries series && id.equals(series.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
