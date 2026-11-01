package kz.tiyn.identity.app;

import java.util.UUID;
import kz.tiyn.identity.FamilyDirectory;
import kz.tiyn.identity.store.LinkStore;
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
}
