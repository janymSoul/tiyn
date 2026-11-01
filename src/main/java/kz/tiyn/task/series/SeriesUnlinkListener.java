package kz.tiyn.task.series;

import kz.tiyn.identity.FamilyUnlinked;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class SeriesUnlinkListener {

    private final SeriesService service;

    SeriesUnlinkListener(SeriesService service) {
        this.service = service;
    }

    @ApplicationModuleListener
    void on(FamilyUnlinked event) {
        service.closeAfterUnlink(event.parentId(), event.teenId());
    }
}
