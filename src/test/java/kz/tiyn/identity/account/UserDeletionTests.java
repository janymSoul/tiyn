package kz.tiyn.identity.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import kz.tiyn.identity.AccountDeleted;
import kz.tiyn.platform.security.Role;
import org.junit.jupiter.api.Test;

class UserDeletionTests {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    @Test
    void deletingAParentAnonymisesTheNameReplacesTheIdentityKeyAndReleasesTheJoinCode() {
        var parent = User.register(UUID.randomUUID(), "google-sub", Role.PARENT, "Aigul", new JoinCode("ABCDEF"));

        parent.delete(NOW);

        assertThat(parent.displayName()).isEqualTo("Deleted user");
        assertThat(parent.idpSub()).isEqualTo("deleted:" + parent.id()).isNotEqualTo("google-sub");
        assertThat(parent.joinCode()).isNull();
        assertThat(parent.isDeleted()).isTrue();
        assertThat(parent.deletedAt()).isEqualTo(NOW);
    }

    @Test
    void deletingKeepsTheIdAndTheRoleSoOtherRecordsStillPointAtSomeone() {
        var id = UUID.randomUUID();
        var teen = User.register(id, "google-sub", Role.TEEN, "Dana", null);

        teen.delete(NOW);

        assertThat(teen.id()).isEqualTo(id);
        assertThat(teen.role()).isEqualTo(Role.TEEN);
    }

    @Test
    void deletingRecordsExactlyOneAccountDeletedEventWithOnlyTheIdAndTheRole() {
        var teen = User.register(UUID.randomUUID(), "google-sub", Role.TEEN, "Dana", null);

        teen.delete(NOW);

        assertThat(teen.pullEvents()).containsExactly(new AccountDeleted(teen.id(), Role.TEEN));
        assertThat(teen.pullEvents()).isEmpty();
    }

    @Test
    void deletingAnAlreadyDeletedAccountChangesNothingAndRecordsNothing() {
        var parent = User.register(UUID.randomUUID(), "google-sub", Role.PARENT, "Aigul", new JoinCode("ABCDEF"));
        parent.delete(NOW);
        parent.pullEvents();

        parent.delete(NOW.plusSeconds(60));

        assertThat(parent.deletedAt()).isEqualTo(NOW);
        assertThat(parent.pullEvents()).isEmpty();
    }

    @Test
    void everyDeletedAccountGetsItsOwnTombstoneSoTheUniqueKeyHolds() {
        var first = User.register(UUID.randomUUID(), "sub-1", Role.TEEN, "A", null);
        var second = User.register(UUID.randomUUID(), "sub-2", Role.TEEN, "B", null);

        first.delete(NOW);
        second.delete(NOW);

        assertThat(first.idpSub()).isNotEqualTo(second.idpSub());
    }
}
