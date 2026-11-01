package kz.tiyn.identity;

import java.util.UUID;

public record FamilyLinked(UUID parentId, UUID teenId, int generation) {}
