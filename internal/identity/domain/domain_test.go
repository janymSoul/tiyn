package domain_test

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"tiyn/internal/identity/domain"
)

type fixedCodes struct{ code domain.JoinCode }

func (f fixedCodes) Next() (domain.JoinCode, error) { return f.code, nil }

type failingCodes struct{}

func (failingCodes) Next() (domain.JoinCode, error) { return "", errors.New("entropy exhausted") }

func newParent(t *testing.T) domain.User {
	t.Helper()
	user, err := domain.NewUser(uuid.New(), "google-parent", domain.RoleParent, "Aigerim", fixedCodes{"K7M4QX"})
	require.NoError(t, err)
	return user
}

func newTeen(t *testing.T) domain.User {
	t.Helper()
	user, err := domain.NewUser(uuid.New(), "google-teen", domain.RoleTeen, "Dana", fixedCodes{"UNUSED"})
	require.NoError(t, err)
	return user
}

func TestParseRoleAcceptsOnlyTheTwoRoles(t *testing.T) {
	for _, valid := range []string{"PARENT", "TEEN"} {
		role, err := domain.ParseRole(valid)
		require.NoError(t, err)
		require.Equal(t, domain.Role(valid), role)
	}
	for _, invalid := range []string{"", "parent", "ADMIN", "PARENT,TEEN", " TEEN"} {
		_, err := domain.ParseRole(invalid)
		require.ErrorIs(t, err, domain.ErrInvalidRole, invalid)
	}
}

func TestGeneratedJoinCodesHaveSixCharactersFromTheUnambiguousAlphabet(t *testing.T) {
	const forbidden = "01ILO"
	seen := map[domain.JoinCode]bool{}
	for range 2000 {
		code, err := domain.RandomJoinCodes{}.Next()
		require.NoError(t, err)
		require.Len(t, code.String(), 6)
		require.False(t, strings.ContainsAny(code.String(), forbidden), code)
		_, err = domain.ParseJoinCode(code.String())
		require.NoError(t, err)
		seen[code] = true
	}
	require.Greater(t, len(seen), 1900)
}

func TestParseJoinCodeNormalisesCaseAndWhitespace(t *testing.T) {
	code, err := domain.ParseJoinCode("  k7m4qx\n")

	require.NoError(t, err)
	require.Equal(t, domain.JoinCode("K7M4QX"), code)
}

func TestParseJoinCodeRejectsWrongLengthAndLookAlikeCharacters(t *testing.T) {
	for _, invalid := range []string{"", "K7M4Q", "K7M4QXX", "K7M4Q0", "K7M4QO", "K7M4Q1", "K7M4QI", "K7M4QL", "K7M4Q-", "К7M4QX"} {
		_, err := domain.ParseJoinCode(invalid)
		require.ErrorIs(t, err, domain.ErrInvalidJoinCode, invalid)
	}
}

func TestNewParentReceivesAJoinCode(t *testing.T) {
	parent := newParent(t)

	code, ok := parent.JoinCode()

	require.True(t, ok)
	require.Equal(t, domain.JoinCode("K7M4QX"), code)
	require.True(t, parent.IsParent())
	require.False(t, parent.IsTeen())
}

func TestNewTeenHasNoJoinCode(t *testing.T) {
	teen := newTeen(t)

	_, ok := teen.JoinCode()

	require.False(t, ok)
	require.True(t, teen.IsTeen())
}

func TestNewUserFailsWhenTheJoinCodeCannotBeGenerated(t *testing.T) {
	_, err := domain.NewUser(uuid.New(), "google", domain.RoleParent, "Aigerim", failingCodes{})

	require.ErrorContains(t, err, "entropy exhausted")
}

func TestNewUserTrimsTheDisplayNameAndEnforcesItsLength(t *testing.T) {
	user, err := domain.NewUser(uuid.New(), "google", domain.RoleTeen, "  Dana  ", fixedCodes{})
	require.NoError(t, err)
	require.Equal(t, "Dana", user.DisplayName())

	for _, invalid := range []string{"", "   ", strings.Repeat("a", 61)} {
		_, err := domain.NewUser(uuid.New(), "google", domain.RoleTeen, invalid, fixedCodes{})
		require.ErrorIs(t, err, domain.ErrInvalidDisplayName, invalid)
	}
	_, err = domain.NewUser(uuid.New(), "google", domain.RoleTeen, strings.Repeat("я", 60), fixedCodes{})
	require.NoError(t, err)
}

func TestNewUserRequiresASubjectAndAValidRole(t *testing.T) {
	_, err := domain.NewUser(uuid.New(), "", domain.RoleTeen, "Dana", fixedCodes{})
	require.ErrorIs(t, err, domain.ErrInvalidSubject)

	_, err = domain.NewUser(uuid.New(), "google", domain.Role("ADMIN"), "Dana", fixedCodes{})
	require.ErrorIs(t, err, domain.ErrInvalidRole)
}

func TestNewFamilyLinkRequiresAParentAndATeen(t *testing.T) {
	parent, teen := newParent(t), newTeen(t)

	link, err := domain.NewFamilyLink(parent, teen)
	require.NoError(t, err)
	require.Equal(t, parent.ID(), link.ParentID())
	require.Equal(t, teen.ID(), link.TeenID())
	require.Equal(t, 1, link.Generation())
	require.True(t, link.IsActive())

	_, err = domain.NewFamilyLink(teen, teen)
	require.ErrorIs(t, err, domain.ErrNotParent)
	_, err = domain.NewFamilyLink(parent, parent)
	require.ErrorIs(t, err, domain.ErrNotTeen)
}

func TestRemovingALinkTwiceChangesNothingTheSecondTime(t *testing.T) {
	link, err := domain.NewFamilyLink(newParent(t), newTeen(t))
	require.NoError(t, err)

	require.True(t, link.Remove())
	require.False(t, link.IsActive())
	require.False(t, link.Remove())
}

func TestActivatingAnActiveLinkChangesNothing(t *testing.T) {
	link, err := domain.NewFamilyLink(newParent(t), newTeen(t))
	require.NoError(t, err)

	require.False(t, link.Activate())
	require.Equal(t, 1, link.Generation())
}

func TestRelinkingARemovedLinkReactivatesItWithANewGeneration(t *testing.T) {
	link, err := domain.NewFamilyLink(newParent(t), newTeen(t))
	require.NoError(t, err)
	require.True(t, link.Remove())

	require.True(t, link.Activate())

	require.True(t, link.IsActive())
	require.Equal(t, 2, link.Generation())
}

func TestRehydratedAggregatesKeepTheirStoredState(t *testing.T) {
	parentID, teenID := uuid.New(), uuid.New()

	link := domain.RehydrateFamilyLink(parentID, teenID, 3, true)
	require.False(t, link.IsActive())
	require.Equal(t, 3, link.Generation())
	require.Equal(t, parentID, link.ParentID())

	user := domain.RehydrateUser(parentID, "google", domain.RoleParent, "Aigerim", "K7M4QX", time.Unix(100, 0))
	code, ok := user.JoinCode()
	require.True(t, ok)
	require.Equal(t, domain.JoinCode("K7M4QX"), code)
	require.Equal(t, time.Unix(100, 0), user.CreatedAt())

	teen := domain.RehydrateUser(teenID, "google-2", domain.RoleTeen, "Dana", "", time.Unix(100, 0))
	_, ok = teen.JoinCode()
	require.False(t, ok)
}
