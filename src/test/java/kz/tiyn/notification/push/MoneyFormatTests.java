package kz.tiyn.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MoneyFormatTests {

    @Test
    void formatsMinorUnitsAsTenge() {
        assertThat(MoneyFormat.tenge(0)).isEqualTo("0 ₸");
        assertThat(MoneyFormat.tenge(100)).isEqualTo("1 ₸");
        assertThat(MoneyFormat.tenge(150_000)).isEqualTo("1 500 ₸");
        assertThat(MoneyFormat.tenge(123_456_789)).isEqualTo("1 234 567,89 ₸");
    }

    @Test
    void keepsTheTiynWhenThereIsAFraction() {
        assertThat(MoneyFormat.tenge(50)).isEqualTo("0,50 ₸");
        assertThat(MoneyFormat.tenge(105)).isEqualTo("1,05 ₸");
        assertThat(MoneyFormat.tenge(-250)).isEqualTo("-2,50 ₸");
    }
}
