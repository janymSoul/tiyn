package kz.tiyn.dashboard.view;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tiyn.dashboard")
record DashboardProperties(Duration recentWindow, int maxTasks, int reviewPreview) {}
