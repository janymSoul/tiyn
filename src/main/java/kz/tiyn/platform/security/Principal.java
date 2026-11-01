package kz.tiyn.platform.security;

import java.util.UUID;

public record Principal(UUID userId, Role role) {}
