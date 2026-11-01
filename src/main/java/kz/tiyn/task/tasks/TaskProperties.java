package kz.tiyn.task.tasks;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tiyn.task")
record TaskProperties(long maxRewardAmount) {}
