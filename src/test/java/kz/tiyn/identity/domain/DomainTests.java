package kz.tiyn.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import kz.tiyn.platform.security.Role;
import org.junit.jupiter.api.Test;

class DomainTests {

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
    void relinkingAnActiveLinkChangesNothing() {
        var link = FamilyLink.create(UUID.randomUUID(), UUID.randomUUID());

        assertThat(link.relink()).isFalse();
        assertThat(link.generation()).isEqualTo(1);
    }

    @Test
    void removeThenRelinkBumpsTheGenerationOnce() {
        var link = FamilyLink.create(UUID.randomUUID(), UUID.randomUUID());

        assertThat(link.remove(Instant.now())).isTrue();
        assertThat(link.remove(Instant.now())).isFalse();
        assertThat(link.relink()).isTrue();
        assertThat(link.isActive()).isTrue();
        assertThat(link.generation()).isEqualTo(2);
    }

    @Test
    void aUserCannotBeLinkedToThemselves() {
        var id = UUID.randomUUID();

        assertThatThrownBy(() -> FamilyLink.create(id, id)).isInstanceOf(IllegalArgumentException.class);
    }
}
