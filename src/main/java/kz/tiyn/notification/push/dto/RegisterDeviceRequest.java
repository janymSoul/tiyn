package kz.tiyn.notification.push.dto;

public record RegisterDeviceRequest(String token, String platform, String locale) {}
