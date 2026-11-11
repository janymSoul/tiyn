package kz.tiyn.task;

import java.util.List;

public record ReviewQueue(long total, List<ReviewTask> tasks) {}
