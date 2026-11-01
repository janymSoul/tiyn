package kz.tiyn.task.tasks;

public interface TaskAssignment {

    void validateTemplate(String title, String description, long rewardAmount, long penaltyAmount, String currency);

    boolean assignOccurrence(Occurrence occurrence);
}
