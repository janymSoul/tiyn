package kz.tiyn.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.UUID;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import kz.tiyn.platform.security.Role;
import kz.tiyn.support.Invisible;
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

    @Test
    void registrationNormalisesTheDisplayNameLikeRenaming() {
        assertThat(User.register(UUID.randomUUID(), "s", Role.TEEN, " " + Invisible.RLO + "Da  na ", null)
                        .displayName())
                .isEqualTo("Da na");
    }

    @Test
    void renameReplacesOnlyTheDisplayName() {
        var id = UUID.randomUUID();
        var user = User.restore(id, "sub", Role.PARENT, "ABCDEF", "Old");

        user.rename("  New   Name ");

        assertThat(user.displayName()).isEqualTo("New Name");
        assertThat(user.id()).isEqualTo(id);
        assertThat(user.role()).isEqualTo(Role.PARENT);
        assertThat(user.joinCode()).isEqualTo("ABCDEF");
        assertThat(user.idpSub()).isEqualTo("sub");
    }

    @Test
    void renameRejectsAnInvalidNameAndKeepsTheOldOne() {
        var user = User.restore(UUID.randomUUID(), "sub", Role.TEEN, null, "Old");

        for (String bad : new String[] {null, " ", Invisible.ZWSP, "x".repeat(101)}) {
            assertThatThrownBy(() -> user.rename(bad))
                    .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.error())
                            .isEqualTo(IdentityError.INVALID_DISPLAY_NAME));
        }
        assertThat(user.displayName()).isEqualTo("Old");
    }

    @Test
    void aParentSwapsToTheNewCodeAndKeepsEverythingElse() {
        UUID id = UUID.randomUUID();
        User parent = User.register(id, "s", Role.PARENT, "Aigul", new JoinCode("ABCDEF"));

        User rotated = parent.withJoinCode(new JoinCode("GHJKLM"));

        assertThat(rotated.joinCode()).isEqualTo("GHJKLM");
        assertThat(rotated.id()).isEqualTo(id);
        assertThat(rotated.role()).isEqualTo(Role.PARENT);
        assertThat(rotated.displayName()).isEqualTo("Aigul");
        assertThat(parent.joinCode()).isEqualTo("ABCDEF");
    }

    @Test
    void aTeenCannotGetAJoinCode() {
        User teen = User.register(UUID.randomUUID(), "s", Role.TEEN, "Dana", null);

        assertThatThrownBy(() -> teen.withJoinCode(new JoinCode("ABCDEF")))
                .isInstanceOfSatisfying(
                        IdentityException.class, e -> assertThat(e.error()).isEqualTo(IdentityError.FORBIDDEN));
    }
}
