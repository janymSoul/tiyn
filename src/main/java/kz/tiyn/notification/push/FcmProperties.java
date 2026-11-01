package kz.tiyn.notification.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tiyn.notification.fcm")
record FcmProperties(String credentialsFile, boolean dryRun) {}
