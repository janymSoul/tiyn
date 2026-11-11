package kz.tiyn.identity.account.dto;

import jakarta.validation.constraints.NotNull;

public record JoinApprovalRequest(@NotNull Boolean required) {}
