package kz.tiyn.task.tasks;

import kz.tiyn.identity.FamilyUnlinked;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class FamilyUnlinkedListener {

    private final TaskService service;

    FamilyUnlinkedListener(TaskService service) {
        this.service = service;
    }

    @ApplicationModuleListener
    void on(FamilyUnlinked event) {
        service.cancelOpenTasksAfterUnlink(event.parentId(), event.teenId());
    }
}
