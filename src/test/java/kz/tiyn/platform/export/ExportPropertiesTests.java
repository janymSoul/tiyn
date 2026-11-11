package kz.tiyn.platform.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExportPropertiesTests {

    @Test
    void aPageHoldsAtLeastOneRow() {
        assertThatThrownBy(() -> new ExportProperties(0, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theIntervalCannotBeNegativeOrMissing() {
        assertThatThrownBy(() -> new ExportProperties(10, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExportProperties(10, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aZeroIntervalSwitchesTheGuardOff() {
        assertThat(new ExportProperties(10, Duration.ZERO).minInterval()).isZero();
    }
}
