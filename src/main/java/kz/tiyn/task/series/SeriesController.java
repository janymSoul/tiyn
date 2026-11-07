package kz.tiyn.task.series;

import java.util.UUID;
import kz.tiyn.platform.idempotency.IdempotencyContext;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.task.series.dto.CreateSeriesRequest;
import kz.tiyn.task.series.dto.SeriesResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SeriesController {

    private final SeriesService service;

    SeriesController(SeriesService service) {
        this.service = service;
    }

    @PostMapping("/task-series")
    @ResponseStatus(HttpStatus.CREATED)
    SeriesResponse create(
            @AuthenticationPrincipal Principal caller,
            IdempotencyContext idempotency,
            @RequestBody CreateSeriesRequest request) {
        TaskSeries s = service.create(caller, idempotency, request);
        return new SeriesResponse(
                s.id(),
                s.teenId(),
                s.title(),
                s.description(),
                s.rewardAmount(),
                s.penaltyAmount(),
                s.currency(),
                s.recurrence().name(),
                s.zone().getId(),
                s.dueTime(),
                s.startsOn(),
                s.isActive());
    }

    @DeleteMapping("/task-series/{id}")
    ResponseEntity<Void> close(@AuthenticationPrincipal Principal caller, @PathVariable UUID id) {
        service.close(caller, id);
        return ResponseEntity.noContent().build();
    }
}
