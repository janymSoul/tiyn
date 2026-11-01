package kz.tiyn.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.UUID;
import kz.tiyn.platform.security.Role;
import org.junit.jupiter.api.Test;

class AccountDomainTests {

    @Test
    void generatedJoinCodesUseOnlyUnambiguousCharacters() {
        var random = new SecureRandom();

        for (int i = 0; i < 500; i++) {
            assertThat(JoinCode.generate(random).value()).hasSize(6).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}");
        }
    }

    @Test
    void joinCodeParsingIgnoresCaseAndSurroundingSpaces() {
        assertThat(JoinCode.parse("  ab2cde ").value()).isEqualTo("AB2CDE");
    }

    @Test
    void lookAlikeShortLongAndNullCodesAreAllTheSameError() {
        for (String bad : new String[] {"AB0CDE", "AB1CDE", "ABOCDE", "ABICDE", "ABCDE", "ABCDEFG", "", null}) {
            assertThatThrownBy(() -> JoinCode.parse(bad))
                    .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.error())
                            .isEqualTo(IdentityError.INVALID_JOIN_CODE));
        }
    }

    @Test
    void parentsGetAJoinCodeAndTeensDoNot() {
        var code = new JoinCode("ABCDEF");

        assertThat(User.register(UUID.randomUUID(), "s1", Role.PARENT, "Aigul", code)
                        .joinCode())
                .isEqualTo("ABCDEF");
        assertThat(User.register(UUID.randomUUID(), "s2", Role.TEEN, "Dana", code)
                        .joinCode())
                .isNull();
    }

    @Test
    void displayNameIsTrimmedAndMustBeNonBlankAndShort() {
        assertThat(User.register(UUID.randomUUID(), "s", Role.TEEN, "  Dana  ", null)
                        .displayName())
                .isEqualTo("Dana");
        for (String bad : new String[] {null, "", "   ", "x".repeat(101)}) {
            assertThatThrownBy(() -> User.register(UUID.randomUUID(), "s", Role.TEEN, bad, null))
                    .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.error())
                            .isEqualTo(IdentityError.INVALID_DISPLAY_NAME));
        }
    }
}
