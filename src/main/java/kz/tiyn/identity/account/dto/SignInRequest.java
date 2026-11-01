package kz.tiyn.identity.account.dto;

public record SignInRequest(String idToken, String role, String displayName) {}
