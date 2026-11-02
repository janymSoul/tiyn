package kz.tiyn.identity.family;

import java.util.UUID;
import kz.tiyn.platform.security.Role;

record FamilyMember(UUID id, Role role, String displayName) {}
