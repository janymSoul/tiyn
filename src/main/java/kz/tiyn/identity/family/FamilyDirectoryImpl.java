package kz.tiyn.identity.family;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import kz.tiyn.identity.FamilyDirectory;
import kz.tiyn.identity.LinkedTeen;
import kz.tiyn.platform.security.Role;
import org.springframework.stereotype.Component;

@Component
class FamilyDirectoryImpl implements FamilyDirectory {

    private final LinkStore links;

    FamilyDirectoryImpl(LinkStore links) {
        this.links = links;
    }

    @Override
    public boolean areLinked(UUID parentId, UUID teenId) {
        return links.existsActive(parentId, teenId);
    }

    @Override
    public Set<UUID> linkedTeenIds(UUID parentId) {
        return links.activeTeenIds(parentId);
    }

    @Override
    public List<LinkedTeen> linkedTeens(UUID parentId) {
        return links.activeMembers(parentId, Role.PARENT).stream()
                .map(m -> new LinkedTeen(m.id(), m.displayName()))
                .toList();
    }
}
