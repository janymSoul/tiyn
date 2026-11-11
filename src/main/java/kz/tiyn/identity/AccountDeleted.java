package kz.tiyn.identity;

import java.util.UUID;
import kz.tiyn.platform.security.Role;

public record AccountDeleted(UUID userId, Role role) {}
