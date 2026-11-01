package kz.tiyn.ledger.wallet;

import kz.tiyn.task.TaskApproved;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class RewardListener {

    private final WalletService service;

    RewardListener(WalletService service) {
        this.service = service;
    }

    @ApplicationModuleListener
    void on(TaskApproved event) {
        service.creditTaskReward(event.taskId(), event.teenId(), event.rewardAmount(), event.currency());
    }
}
