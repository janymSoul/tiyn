package kz.tiyn.platform.security;

import java.io.Serializable;
import java.util.UUID;

public record Principal(UUID userId, Role role) implements Serializable {}
