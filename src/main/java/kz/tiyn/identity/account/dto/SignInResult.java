package kz.tiyn.identity.account.dto;

import kz.tiyn.identity.account.User;

public record SignInResult(String token, String refreshToken, User user) {}
