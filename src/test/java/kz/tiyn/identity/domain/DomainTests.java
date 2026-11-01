package kz.tiyn.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.tiyn.identity.FamilyLinked;
import kz.tiyn.identity.FamilyUnlinked;
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
    void firstLinkIsGenerationOneAndRecordsOneEvent() {
        var parent = user(Role.PARENT);
        var teen = user(Role.TEEN);
        var link = FamilyLink.between(parent, teen);

        link.link();

        assertThat(link.isActive()).isTrue();
        assertThat(link.pullEvents()).containsExactly(new FamilyLinked(parent.id(), teen.id(), 1));
        assertThat(link.pullEvents()).isEmpty();
    }

    @Test
    void linkingAnActiveLinkChangesNothingAndRecordsNothing() {
        var link = FamilyLink.between(user(Role.PARENT), user(Role.TEEN));
        link.link();
        link.pullEvents();

        link.link();

        assertThat(link.generation()).isEqualTo(1);
        assertThat(link.pullEvents()).isEmpty();
    }

    @Test
    void unlinkThenRelinkBumpsTheGenerationOnceAndEachStepRecordsItsEvent() {
        var parent = user(Role.PARENT);
        var teen = user(Role.TEEN);
        var link = FamilyLink.between(parent, teen);
        link.link();

        link.unlink(Instant.now());
        link.unlink(Instant.now());
        link.link();

        assertThat(link.isActive()).isTrue();
        assertThat(link.generation()).isEqualTo(2);
        assertThat(link.pullEvents())
                .containsExactly(
                        new FamilyLinked(parent.id(), teen.id(), 1),
                        new FamilyUnlinked(parent.id(), teen.id(), 1),
                        new FamilyLinked(parent.id(), teen.id(), 2));
    }

    @Test
    void aLinkNeverMadeCannotBeUnlinked() {
        var link = FamilyLink.between(user(Role.PARENT), user(Role.TEEN));

        link.unlink(Instant.now());

        assertThat(link.pullEvents()).isEmpty();
    }

    @Test
    void onlyAParentAndATeenCanBeLinked() {
        for (var pair : List.of(
                List.of(Role.TEEN, Role.TEEN), List.of(Role.PARENT, Role.PARENT), List.of(Role.TEEN, Role.PARENT))) {
            assertThatThrownBy(() -> FamilyLink.between(user(pair.get(0)), user(pair.get(1))))
                    .isInstanceOfSatisfying(
                            IdentityException.class, e -> assertThat(e.error()).isEqualTo(IdentityError.FORBIDDEN));
        }
    }

    private static User user(Role role) {
        return User.register(UUID.randomUUID(), "sub-" + UUID.randomUUID(), role, "Name", new JoinCode("ABCDEF"));
    }
}
