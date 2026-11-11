package kz.tiyn.identity.account;

import static kz.tiyn.support.Invisible.ALM;
import static kz.tiyn.support.Invisible.BEL;
import static kz.tiyn.support.Invisible.BYTE_ORDER_MARK;
import static kz.tiyn.support.Invisible.DEL;
import static kz.tiyn.support.Invisible.EMOJI;
import static kz.tiyn.support.Invisible.EM_SPACE;
import static kz.tiyn.support.Invisible.ESC;
import static kz.tiyn.support.Invisible.IDEOGRAPHIC_SPACE;
import static kz.tiyn.support.Invisible.LINE_SEPARATOR;
import static kz.tiyn.support.Invisible.LONE_SURROGATE;
import static kz.tiyn.support.Invisible.LRE;
import static kz.tiyn.support.Invisible.LRI;
import static kz.tiyn.support.Invisible.LRM;
import static kz.tiyn.support.Invisible.LRO;
import static kz.tiyn.support.Invisible.NBSP;
import static kz.tiyn.support.Invisible.NEL;
import static kz.tiyn.support.Invisible.NUL;
import static kz.tiyn.support.Invisible.PDF;
import static kz.tiyn.support.Invisible.PDI;
import static kz.tiyn.support.Invisible.RLE;
import static kz.tiyn.support.Invisible.RLM;
import static kz.tiyn.support.Invisible.RLO;
import static kz.tiyn.support.Invisible.WORD_JOINER;
import static kz.tiyn.support.Invisible.ZWJ;
import static kz.tiyn.support.Invisible.ZWNJ;
import static kz.tiyn.support.Invisible.ZWSP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import org.junit.jupiter.api.Test;

class DisplayNameTests {

    @Test
    void surroundingWhitespaceIsTrimmedAndInnerRunsCollapseToOneSpace() {
        assertThat(DisplayName.normalise("  Dana \t  Aibek\n\nUly  ")).isEqualTo("Dana Aibek Uly");
    }

    @Test
    void unicodeSpacesCountAsWhitespace() {
        String raw = NBSP + "Dana" + IDEOGRAPHIC_SPACE + EM_SPACE + "Aibek" + LINE_SEPARATOR;

        assertThat(DisplayName.normalise(raw)).isEqualTo("Dana Aibek");
    }

    @Test
    void controlCharactersAreRemoved() {
        String raw = "Da" + NUL + "na" + BEL + ESC + "[31m" + DEL + NEL;

        assertThat(DisplayName.normalise(raw)).isEqualTo("Dana[31m");
    }

    @Test
    void bidiOverridesIsolatesAndMarksAreRemoved() {
        String hostile = RLO + "naD" + PDF + " " + LRI + "Aibek" + PDI + LRM + RLM + ALM + LRE + RLE + LRO;

        assertThat(DisplayName.normalise(hostile)).isEqualTo("naD Aibek");
    }

    @Test
    void zeroWidthAndByteOrderFormatCharactersAreRemoved() {
        String raw = "Da" + ZWSP + ZWNJ + ZWJ + WORD_JOINER + BYTE_ORDER_MARK + "na";

        assertThat(DisplayName.normalise(raw)).isEqualTo("Dana");
    }

    @Test
    void removedCharactersBetweenSpacesDoNotLeaveDoubleSpaces() {
        assertThat(DisplayName.normalise("Dana " + ZWSP + " Aibek")).isEqualTo("Dana Aibek");
    }

    @Test
    void loneSurrogatesAreRemovedButPairsSurvive() {
        assertThat(DisplayName.normalise("Da" + LONE_SURROGATE + "na")).isEqualTo("Dana");
        assertThat(DisplayName.normalise("Dana " + EMOJI)).isEqualTo("Dana " + EMOJI);
    }

    @Test
    void cyrillicAndKazakhLettersAreUntouched() {
        assertThat(DisplayName.normalise("Әйгерім Жақсылықова")).isEqualTo("Әйгерім Жақсылықова");
    }

    @Test
    void nullNormalisesToEmpty() {
        assertThat(DisplayName.normalise(null)).isEmpty();
    }

    @Test
    void parseReturnsTheNormalisedValue() {
        assertThat(DisplayName.parse("  " + RLO + "Dana  ").value()).isEqualTo("Dana");
    }

    @Test
    void parseMeasuresTheMaximumLengthAfterNormalising() {
        assertThat(DisplayName.parse("x".repeat(100)).value()).hasSize(100);
        assertThat(DisplayName.parse("x".repeat(100) + ZWSP + "   ").value()).hasSize(100);
    }

    @Test
    void parseRejectsNullBlankOnlyInvisibleAndTooLongNames() {
        String[] invalid = {null, "", "   ", ZWSP + RLO + NUL, " \t\n ", "x".repeat(101)};
        for (String bad : invalid) {
            assertThatThrownBy(() -> DisplayName.parse(bad))
                    .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.error())
                            .isEqualTo(IdentityError.INVALID_DISPLAY_NAME));
        }
    }
}
