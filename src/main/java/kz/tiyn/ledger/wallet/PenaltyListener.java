package kz.tiyn.ledger.wallet;

import kz.tiyn.task.TaskExpired;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class PenaltyListener {

    private final PenaltyService service;

    PenaltyListener(PenaltyService service) {
        this.service = service;
    }

    @ApplicationModuleListener
    void on(TaskExpired event) {
        service.applyMissedTaskPenalty(event.taskId(), event.teenId(), event.penaltyAmount(), event.currency());
    }
}
