package kz.tiyn.ledger.wallet.dto;

public record AdjustmentRequest(String kind, Long amount, String reason) {}
