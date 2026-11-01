package kz.tiyn.task.tasks;

import kz.tiyn.task.tasks.error.TaskError;
import kz.tiyn.task.tasks.error.TaskException;

record Money(long amount, String currency) {

    static final String KZT = "KZT";

    static long penalty(long amount, long maxAmount) {
        if (amount < 0 || amount > maxAmount) {
            throw new TaskException(TaskError.INVALID_PENALTY);
        }
        return amount;
    }

    static Money reward(long amount, String currency, long maxAmount) {
        if (!KZT.equals(currency) || amount <= 0 || amount > maxAmount) {
            throw new TaskException(TaskError.INVALID_REWARD);
        }
        return new Money(amount, currency);
    }
}
