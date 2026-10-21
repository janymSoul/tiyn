package identity_test

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/require"

	"tiyn/internal/identity"
	"tiyn/internal/identity/domain"
	"tiyn/internal/platform/db"
	"tiyn/internal/platform/testdb"
)

func TestMain(m *testing.M) {
	testdb.Main(m, identity.Migrations)
}

type fakeGoogle struct {
	mu         sync.Mutex
	identities map[string]identity.GoogleIdentity
}

func (f *fakeGoogle) register(token, subject, name string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.identities[token] = identity.GoogleIdentity{Subject: subject, Name: name}
}

func (f *fakeGoogle) Verify(_ context.Context, token string) (identity.GoogleIdentity, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	found, ok := f.identities[token]
	if !ok {
		return identity.GoogleIdentity{}, errors.New("token rejected")
	}
	return found, nil
}

type queuedCodes struct {
	mu    sync.Mutex
	queue []domain.JoinCode
}

func (q *queuedCodes) Next() (domain.JoinCode, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if len(q.queue) > 0 {
		code := q.queue[0]
		q.queue = q.queue[1:]
		return code, nil
	}
	return domain.RandomJoinCodes{}.Next()
}

type harness struct {
	pool    *pgxpool.Pool
	service *identity.Service
	google  *fakeGoogle
	codes   *queuedCodes
}

func newHarness(t *testing.T) *harness {
	t.Helper()
	pool := testdb.New(t)
	tokens, err := identity.NewTokenIssuer(testSecret, 7*24*time.Hour, time.Now)
	require.NoError(t, err)
	google := &fakeGoogle{identities: map[string]identity.GoogleIdentity{}}
	codes := &queuedCodes{}
	return &harness{
		pool:    pool,
		service: identity.NewService(db.NewStore(pool), tokens, google, identity.WithJoinCodes(codes)),
		google:  google,
		codes:   codes,
	}
}

func (h *harness) signIn(t *testing.T, subject, role string) identity.SignInResult {
	t.Helper()
	h.google.register("token-"+subject, subject, "Name of "+subject)
	result, err := h.service.SignInWithGoogle(context.Background(), identity.SignInInput{IDToken: "token-" + subject, Role: role})
	require.NoError(t, err)
	return result
}

func principalOf(result identity.SignInResult) identity.Principal {
	return identity.Principal{UserID: result.User.ID, Role: result.User.Role}
}

type outboxRow struct {
	eventType string
	key       string
}

func (h *harness) outbox(t *testing.T) []outboxRow {
	t.Helper()
	rows, err := h.pool.Query(context.Background(), `SELECT event_type, idempotency_key FROM platform.outbox_events ORDER BY id`)
	require.NoError(t, err)
	defer rows.Close()
	var result []outboxRow
	for rows.Next() {
		var row outboxRow
		require.NoError(t, rows.Scan(&row.eventType, &row.key))
		result = append(result, row)
	}
	require.NoError(t, rows.Err())
	return result
}

func (h *harness) count(t *testing.T, query string, args ...any) int {
	t.Helper()
	var n int
	require.NoError(t, h.pool.QueryRow(context.Background(), query, args...).Scan(&n))
	return n
}

func TestFirstSignInOfAParentCreatesAnAccountWithAJoinCodeAndAToken(t *testing.T) {
	h := newHarness(t)

	result := h.signIn(t, "g-parent", "PARENT")

	require.Equal(t, domain.RoleParent, result.User.Role)
	require.Len(t, result.User.JoinCode, 6)
	require.Equal(t, "Name of g-parent", result.User.DisplayName)
	principal, err := h.service.Verify(context.Background(), result.Token)
	require.NoError(t, err)
	require.Equal(t, principalOf(result), principal)
}

func TestFirstSignInOfATeenCreatesAnAccountWithoutAJoinCode(t *testing.T) {
	h := newHarness(t)

	result := h.signIn(t, "g-teen", "TEEN")

	require.Equal(t, domain.RoleTeen, result.User.Role)
	require.Empty(t, result.User.JoinCode)
}

func TestSigningInAgainReturnsTheSameAccountAndIgnoresTheRequestedRole(t *testing.T) {
	h := newHarness(t)
	first := h.signIn(t, "g-teen", "TEEN")

	second := h.signIn(t, "g-teen", "PARENT")

	require.Equal(t, first.User.ID, second.User.ID)
	require.Equal(t, domain.RoleTeen, second.User.Role)
	require.Empty(t, second.User.JoinCode)
	require.Equal(t, 1, h.count(t, `SELECT count(*) FROM identity.users`))
}

func TestFirstSignInWithoutAValidRoleCreatesNothing(t *testing.T) {
	h := newHarness(t)
	h.google.register("token-x", "g-x", "X")

	for _, role := range []string{"", "ADMIN", "parent"} {
		_, err := h.service.SignInWithGoogle(context.Background(), identity.SignInInput{IDToken: "token-x", Role: role})
		require.ErrorIs(t, err, domain.ErrInvalidRole, role)
	}
	require.Equal(t, 0, h.count(t, `SELECT count(*) FROM identity.users`))
}

func TestDisplayNameFromTheRequestWinsOverTheGoogleName(t *testing.T) {
	h := newHarness(t)
	h.google.register("token-x", "g-x", "Google Name")

	result, err := h.service.SignInWithGoogle(context.Background(),
		identity.SignInInput{IDToken: "token-x", Role: "TEEN", DisplayName: "  Dana  "})

	require.NoError(t, err)
	require.Equal(t, "Dana", result.User.DisplayName)
}

func TestFirstSignInWithoutAnyUsableNameIsRejected(t *testing.T) {
	h := newHarness(t)
	h.google.register("token-x", "g-x", "")

	_, err := h.service.SignInWithGoogle(context.Background(), identity.SignInInput{IDToken: "token-x", Role: "TEEN"})

	require.ErrorIs(t, err, domain.ErrInvalidDisplayName)
}

func TestRejectedGoogleTokenCreatesNothing(t *testing.T) {
	h := newHarness(t)

	_, err := h.service.SignInWithGoogle(context.Background(), identity.SignInInput{IDToken: "forged", Role: "PARENT"})

	require.ErrorIs(t, err, identity.ErrInvalidIdentity)
	require.Equal(t, 0, h.count(t, `SELECT count(*) FROM identity.users`))
}

func TestConcurrentFirstSignInsOfOneGoogleAccountCreateExactlyOneUser(t *testing.T) {
	h := newHarness(t)
	h.google.register("token-x", "g-x", "X")
	const callers = 8

	var wg sync.WaitGroup
	ids := make([]uuid.UUID, callers)
	errs := make([]error, callers)
	for i := range callers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			result, err := h.service.SignInWithGoogle(context.Background(), identity.SignInInput{IDToken: "token-x", Role: "PARENT"})
			ids[i], errs[i] = result.User.ID, err
		}()
	}
	wg.Wait()

	for i := range callers {
		require.NoError(t, errs[i])
		require.Equal(t, ids[0], ids[i])
	}
	require.Equal(t, 1, h.count(t, `SELECT count(*) FROM identity.users`))
}

func TestAJoinCodeCollisionIsRetriedWithAFreshCode(t *testing.T) {
	h := newHarness(t)
	first := h.signIn(t, "g-parent-1", "PARENT")
	h.codes.queue = []domain.JoinCode{domain.JoinCode(first.User.JoinCode), "K7M4QX"}

	second := h.signIn(t, "g-parent-2", "PARENT")

	require.Equal(t, "K7M4QX", second.User.JoinCode)
	require.Equal(t, 2, h.count(t, `SELECT count(DISTINCT join_code) FROM identity.users`))
}

func TestTeenJoinsWithAParentCodeAndOneEventIsQueued(t *testing.T) {
	h := newHarness(t)
	parent := h.signIn(t, "g-parent", "PARENT")
	teen := h.signIn(t, "g-teen", "TEEN")

	joined, err := h.service.JoinWithCode(context.Background(), principalOf(teen), parent.User.JoinCode)

	require.NoError(t, err)
	require.Equal(t, "Name of g-parent", joined.ParentDisplayName)
	linked, err := h.service.AreLinked(context.Background(), parent.User.ID, teen.User.ID)
	require.NoError(t, err)
	require.True(t, linked)
	require.Equal(t, []outboxRow{{
		identity.FamilyLinkedEventType,
		"FAMILY-LINKED-" + parent.User.ID.String() + "-" + teen.User.ID.String() + "-1",
	}}, h.outbox(t))
}

func TestJoiningAgainChangesNothingAndQueuesNoSecondEvent(t *testing.T) {
	h := newHarness(t)
	parent := h.signIn(t, "g-parent", "PARENT")
	teen := h.signIn(t, "g-teen", "TEEN")
	_, err := h.service.JoinWithCode(context.Background(), principalOf(teen), parent.User.JoinCode)
	require.NoError(t, err)

	_, err = h.service.JoinWithCode(context.Background(), principalOf(teen), parent.User.JoinCode)

	require.NoError(t, err)
	require.Len(t, h.outbox(t), 1)
	require.Equal(t, 1, h.count(t, `SELECT count(*) FROM identity.family_links`))
}

func TestJoinCodeIsAcceptedInAnyCaseWithSurroundingSpaces(t *testing.T) {
	h := newHarness(t)
	parent := h.signIn(t, "g-parent", "PARENT")
	teen := h.signIn(t, "g-teen", "TEEN")

	_, err := h.service.JoinWithCode(context.Background(), principalOf(teen), " "+lower(parent.User.JoinCode)+" ")

	require.NoError(t, err)
}

func lower(value string) string {
	out := []byte(value)
	for i, c := range out {
		if c >= 'A' && c <= 'Z' {
			out[i] = c + 'a' - 'A'
		}
	}
	return string(out)
}

func TestUnknownOrMalformedCodesAreRejectedTheSameWay(t *testing.T) {
	h := newHarness(t)
	h.signIn(t, "g-parent", "PARENT")
	teen := h.signIn(t, "g-teen", "TEEN")

	for _, code := range []string{"ZZZZZZ", "", "K7M4Q", "K7M4Q0"} {
		_, err := h.service.JoinWithCode(context.Background(), principalOf(teen), code)
		require.ErrorIs(t, err, domain.ErrInvalidJoinCode, code)
	}
	require.Equal(t, 0, h.count(t, `SELECT count(*) FROM identity.family_links`))
	require.Empty(t, h.outbox(t))
}

func TestOnlyATeenMayJoinWithACode(t *testing.T) {
	h := newHarness(t)
	parent := h.signIn(t, "g-parent", "PARENT")
	other := h.signIn(t, "g-other", "PARENT")

	_, err := h.service.JoinWithCode(context.Background(), principalOf(other), parent.User.JoinCode)

	require.ErrorIs(t, err, identity.ErrForbidden)
	require.Equal(t, 0, h.count(t, `SELECT count(*) FROM identity.family_links`))
}

func TestATokenForAUserThatDoesNotExistCannotJoin(t *testing.T) {
	h := newHarness(t)
	parent := h.signIn(t, "g-parent", "PARENT")
	ghost := identity.Principal{UserID: uuid.New(), Role: domain.RoleTeen}

	_, err := h.service.JoinWithCode(context.Background(), ghost, parent.User.JoinCode)

	require.ErrorIs(t, err, identity.ErrUnauthenticated)
}

func TestATeenCanBeLinkedToTwoParentsAndEachLinkIsIndependent(t *testing.T) {
	h := newHarness(t)
	mother := h.signIn(t, "g-mother", "PARENT")
	father := h.signIn(t, "g-father", "PARENT")
	teen := h.signIn(t, "g-teen", "TEEN")
	ctx := context.Background()
	_, err := h.service.JoinWithCode(ctx, principalOf(teen), mother.User.JoinCode)
	require.NoError(t, err)
	_, err = h.service.JoinWithCode(ctx, principalOf(teen), father.User.JoinCode)
	require.NoError(t, err)

	require.NoError(t, h.service.RemoveLink(ctx, principalOf(mother), teen.User.ID))

	motherLinked, _ := h.service.AreLinked(ctx, mother.User.ID, teen.User.ID)
	fatherLinked, _ := h.service.AreLinked(ctx, father.User.ID, teen.User.ID)
	require.False(t, motherLinked)
	require.True(t, fatherLinked)
}

func TestRemovingALinkQueuesOneUnlinkedEventEvenWhenRepeated(t *testing.T) {
	h := newHarness(t)
	parent := h.signIn(t, "g-parent", "PARENT")
	teen := h.signIn(t, "g-teen", "TEEN")
	ctx := context.Background()
	_, err := h.service.JoinWithCode(ctx, principalOf(teen), parent.User.JoinCode)
	require.NoError(t, err)

	require.NoError(t, h.service.RemoveLink(ctx, principalOf(parent), teen.User.ID))
	require.NoError(t, h.service.RemoveLink(ctx, principalOf(parent), teen.User.ID))

	linked, _ := h.service.AreLinked(ctx, parent.User.ID, teen.User.ID)
	require.False(t, linked)
	rows := h.outbox(t)
	require.Len(t, rows, 2)
	require.Equal(t, identity.FamilyUnlinkedEventType, rows[1].eventType)
	require.Equal(t, "FAMILY-UNLINKED-"+parent.User.ID.String()+"-"+teen.User.ID.String()+"-1", rows[1].key)
}

func TestRemovingALinkThatNeverExistedIsNotFound(t *testing.T) {
	h := newHarness(t)
	parent := h.signIn(t, "g-parent", "PARENT")
	teen := h.signIn(t, "g-teen", "TEEN")

	err := h.service.RemoveLink(context.Background(), principalOf(parent), teen.User.ID)

	require.ErrorIs(t, err, identity.ErrNotFound)
}

func TestOnlyAParentMayRemoveALink(t *testing.T) {
	h := newHarness(t)
	parent := h.signIn(t, "g-parent", "PARENT")
	teen := h.signIn(t, "g-teen", "TEEN")
	ctx := context.Background()
	_, err := h.service.JoinWithCode(ctx, principalOf(teen), parent.User.JoinCode)
	require.NoError(t, err)

	err = h.service.RemoveLink(ctx, principalOf(teen), teen.User.ID)

	require.ErrorIs(t, err, identity.ErrForbidden)
	linked, _ := h.service.AreLinked(ctx, parent.User.ID, teen.User.ID)
	require.True(t, linked)
}

func TestRelinkingAfterRemovalReactivatesTheSameRowWithANewEventKey(t *testing.T) {
	h := newHarness(t)
	parent := h.signIn(t, "g-parent", "PARENT")
	teen := h.signIn(t, "g-teen", "TEEN")
	ctx := context.Background()
	_, err := h.service.JoinWithCode(ctx, principalOf(teen), parent.User.JoinCode)
	require.NoError(t, err)
	require.NoError(t, h.service.RemoveLink(ctx, principalOf(parent), teen.User.ID))

	_, err = h.service.JoinWithCode(ctx, principalOf(teen), parent.User.JoinCode)

	require.NoError(t, err)
	linked, _ := h.service.AreLinked(ctx, parent.User.ID, teen.User.ID)
	require.True(t, linked)
	require.Equal(t, 1, h.count(t, `SELECT count(*) FROM identity.family_links WHERE generation = 2`))
	rows := h.outbox(t)
	require.Len(t, rows, 3)
	require.Equal(t, "FAMILY-LINKED-"+parent.User.ID.String()+"-"+teen.User.ID.String()+"-2", rows[2].key)
}

func TestConcurrentJoinsOfOnePairCreateOneLinkAndOneEvent(t *testing.T) {
	h := newHarness(t)
	parent := h.signIn(t, "g-parent", "PARENT")
	teen := h.signIn(t, "g-teen", "TEEN")
	const callers = 8

	var wg sync.WaitGroup
	errs := make([]error, callers)
	for i := range callers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, errs[i] = h.service.JoinWithCode(context.Background(), principalOf(teen), parent.User.JoinCode)
		}()
	}
	wg.Wait()

	for _, err := range errs {
		require.NoError(t, err)
	}
	require.Equal(t, 1, h.count(t, `SELECT count(*) FROM identity.family_links`))
	require.Len(t, h.outbox(t), 1)
}

func TestJoinThatLosesTheRaceForTheLinkRowRetriesAndSucceedsWithoutASecondEvent(t *testing.T) {
	h := newHarness(t)
	parent := h.signIn(t, "g-parent", "PARENT")
	teen := h.signIn(t, "g-teen", "TEEN")
	ctx := context.Background()
	rival, err := h.pool.Begin(ctx)
	require.NoError(t, err)
	_, err = rival.Exec(ctx, `INSERT INTO identity.family_links (parent_id, teen_id) VALUES ($1, $2)`, parent.User.ID, teen.User.ID)
	require.NoError(t, err)

	done := make(chan error, 1)
	go func() {
		_, err := h.service.JoinWithCode(ctx, principalOf(teen), parent.User.JoinCode)
		done <- err
	}()
	require.Eventually(t, func() bool {
		return h.count(t, `SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%INSERT INTO identity.family_links%'`) == 1
	}, 2*time.Second, 10*time.Millisecond)
	require.NoError(t, rival.Commit(ctx))

	select {
	case err := <-done:
		require.NoError(t, err)
	case <-time.After(5 * time.Second):
		t.Fatal("join did not finish after the rival transaction committed")
	}
	require.Equal(t, 1, h.count(t, `SELECT count(*) FROM identity.family_links`))
	require.Empty(t, h.outbox(t))
}

func TestUnlinkedPairsAreNotLinked(t *testing.T) {
	h := newHarness(t)
	parent := h.signIn(t, "g-parent", "PARENT")
	teen := h.signIn(t, "g-teen", "TEEN")

	linked, err := h.service.AreLinked(context.Background(), parent.User.ID, teen.User.ID)

	require.NoError(t, err)
	require.False(t, linked)
}

func TestMeReturnsTheCallersAccountAndRejectsUnknownUsers(t *testing.T) {
	h := newHarness(t)
	parent := h.signIn(t, "g-parent", "PARENT")

	view, err := h.service.Me(context.Background(), principalOf(parent))
	require.NoError(t, err)
	require.Equal(t, parent.User, view)

	_, err = h.service.Me(context.Background(), identity.Principal{UserID: uuid.New(), Role: domain.RoleTeen})
	require.ErrorIs(t, err, identity.ErrUnauthenticated)
}

func TestServiceAsAuthenticatorRejectsGarbage(t *testing.T) {
	h := newHarness(t)

	_, err := h.service.Verify(context.Background(), "garbage")

	require.ErrorIs(t, err, identity.ErrUnauthenticated)
}
