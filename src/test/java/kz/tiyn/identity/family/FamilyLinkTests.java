package kz.tiyn.identity.family;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.tiyn.identity.FamilyLinked;
import kz.tiyn.identity.FamilyUnlinked;
import kz.tiyn.identity.account.User;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import kz.tiyn.platform.security.Role;
import org.junit.jupiter.api.Test;

class FamilyLinkTests {

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
        return new User(UUID.randomUUID(), "sub-" + UUID.randomUUID(), role, null, "Name");
    }
}
