package kz.tiyn.notification.push;

import java.util.Map;

record Push(String title, String body, Map<String, String> data, boolean highPriority) {}
