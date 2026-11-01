package kz.tiyn.task.tasks;

public interface TaskAssignment {

    void validateTemplate(String title, String description, long rewardAmount, String currency);

    boolean assignOccurrence(Occurrence occurrence);
}
