package kz.tiyn.task.tasks.error;

public class TaskException extends RuntimeException {

    private final TaskError error;

    public TaskException(TaskError error) {
        super(error.code());
        this.error = error;
    }

    public TaskError error() {
        return error;
    }
}
