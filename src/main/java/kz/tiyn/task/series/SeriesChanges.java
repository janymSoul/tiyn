package kz.tiyn.task.series;

import java.time.LocalTime;

record SeriesChanges(
        String title, String description, Long rewardAmount, Long penaltyAmount, LocalTime dueTime, String timezone) {

    boolean touchesTemplate() {
        return title != null || description != null || rewardAmount != null || penaltyAmount != null;
    }
}
