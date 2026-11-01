package kz.tiyn.identity;

import java.util.UUID;

public record FamilyUnlinked(UUID parentId, UUID teenId, int generation) {}
