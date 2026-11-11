package kz.tiyn.identity.account.dto;

public record SignInResponse(String token, UserResponse user, String refreshToken) {}
