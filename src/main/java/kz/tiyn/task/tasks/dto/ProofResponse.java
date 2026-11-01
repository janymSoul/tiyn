package kz.tiyn.task.tasks.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProofResponse(int attemptNo, String imageUrl, String text, Instant createdAt) {}
