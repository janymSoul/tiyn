package kz.tiyn.task.tasks;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.task.tasks.dto.CreateTaskRequest;
import kz.tiyn.task.tasks.dto.NoteRequest;
import kz.tiyn.task.tasks.dto.ProofResponse;
import kz.tiyn.task.tasks.dto.RedoRequest;
import kz.tiyn.task.tasks.dto.TaskResponse;
import kz.tiyn.task.tasks.dto.TextProofRequest;
import kz.tiyn.task.tasks.error.TaskError;
import kz.tiyn.task.tasks.error.TaskException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class TaskController {

    private final TaskService service;

    TaskController(TaskService service) {
        this.service = service;
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    TaskResponse create(@AuthenticationPrincipal Principal caller, @RequestBody CreateTaskRequest request) {
        return toResponse(service.create(caller, request));
    }

    @GetMapping("/tasks")
    List<TaskResponse> list(
            @AuthenticationPrincipal Principal caller,
            @RequestParam(required = false) UUID teenId,
            @RequestParam(required = false) String state) {
        return service.list(caller, teenId, parseState(state)).stream()
                .map(TaskController::toResponse)
                .toList();
    }

    @GetMapping("/tasks/{id}")
    TaskResponse get(@AuthenticationPrincipal Principal caller, @PathVariable UUID id) {
        Task task = service.get(caller, id);
        return task.kind() == TaskKind.MILESTONE ? toResponse(task, service.progress(task)) : toResponse(task);
    }

    @PostMapping(value = "/tasks/{id}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    TaskResponse submitText(
            @AuthenticationPrincipal Principal caller,
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody TextProofRequest request) {
        return toResponse(service.submitText(caller, id, key, request.text()));
    }

    @PostMapping(value = "/tasks/{id}/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    TaskResponse submitImage(
            @AuthenticationPrincipal Principal caller,
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestParam("file") MultipartFile file)
            throws IOException {
        try (var body = file.getInputStream()) {
            return toResponse(service.submitImage(caller, id, key, file.getContentType(), body));
        }
    }

    @PostMapping("/tasks/{id}/approve")
    TaskResponse approve(@AuthenticationPrincipal Principal caller, @PathVariable UUID id) {
        return toResponse(service.approve(caller, id));
    }

    @PostMapping("/tasks/{id}/reject")
    TaskResponse reject(
            @AuthenticationPrincipal Principal caller,
            @PathVariable UUID id,
            @RequestBody(required = false) NoteRequest request) {
        return toResponse(service.reject(caller, id, note(request)));
    }

    @PostMapping("/tasks/{id}/request-redo")
    TaskResponse requestRedo(
            @AuthenticationPrincipal Principal caller,
            @PathVariable UUID id,
            @RequestBody(required = false) RedoRequest request) {
        return toResponse(service.requestRedo(
                caller, id, request == null ? null : request.note(), request == null ? null : request.deadlineAt()));
    }

    @PostMapping("/tasks/{id}/cancel")
    TaskResponse cancel(@AuthenticationPrincipal Principal caller, @PathVariable UUID id) {
        return toResponse(service.cancel(caller, id));
    }

    @GetMapping("/tasks/{id}/proofs")
    List<ProofResponse> proofs(@AuthenticationPrincipal Principal caller, @PathVariable UUID id) {
        return service.proofs(caller, id);
    }

    private static String note(NoteRequest request) {
        return request == null ? null : request.note();
    }

    private static TaskState parseState(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TaskState.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new TaskException(TaskError.INVALID_FILTER);
        }
    }

    private static TaskResponse toResponse(Task t) {
        return toResponse(t, null);
    }

    private static TaskResponse toResponse(Task t, MilestoneProgress progress) {
        return new TaskResponse(
                t.id(),
                t.teenId(),
                t.parentId(),
                t.kind().name(),
                t.title(),
                t.description(),
                t.reward().amount(),
                t.reward().currency(),
                t.state().name(),
                t.deadlineAt(),
                t.submittedAt(),
                t.decidedAt(),
                t.decisionNote(),
                t.milestoneId(),
                progress == null ? null : progress.approvedTasks(),
                progress == null ? null : progress.totalTasks());
    }
}
