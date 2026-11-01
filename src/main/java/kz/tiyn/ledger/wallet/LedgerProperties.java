package kz.tiyn.ledger.wallet;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tiyn.ledger")
record LedgerProperties(long minPayoutAmount) {}
