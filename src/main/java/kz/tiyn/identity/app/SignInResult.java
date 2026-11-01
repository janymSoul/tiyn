package kz.tiyn.identity.app;

import kz.tiyn.identity.domain.User;

public record SignInResult(String token, User user) {}
