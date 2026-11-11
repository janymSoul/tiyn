package kz.tiyn.identity.family.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FamilyLinkExport(UUID id, String displayName, Instant linkedAt, Instant removedAt) {}
