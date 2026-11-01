package kz.tiyn.ledger.wallet.dto;

import java.util.UUID;

public record RequestPayoutRequest(UUID parentId, Long amount) {}
