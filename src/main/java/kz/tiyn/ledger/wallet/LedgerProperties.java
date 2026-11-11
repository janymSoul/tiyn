package kz.tiyn.ledger.wallet;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tiyn.ledger")
record LedgerProperties(long minPayoutAmount, Duration payoutExpiry, long maxAdjustmentAmount) {}
