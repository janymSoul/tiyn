package kz.tiyn.ledger.wallet;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LedgerProperties.class)
class LedgerConfig {}
