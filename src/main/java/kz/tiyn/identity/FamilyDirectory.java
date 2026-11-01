package kz.tiyn.identity;

import java.util.UUID;

public interface FamilyDirectory {

    boolean areLinked(UUID parentId, UUID teenId);
}
