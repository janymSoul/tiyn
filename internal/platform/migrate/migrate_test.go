package migrate_test

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/pressly/goose/v3/lock"
	"github.com/stretchr/testify/require"

	"tiyn/internal/platform/migrate"
	platformmigrations "tiyn/internal/platform/migrations"
	"tiyn/internal/platform/testdb"
)

func TestMain(m *testing.M) {
	testdb.Main(m)
}

func TestMigrationWaitsWhileAnotherRunnerHoldsTheMigrationLock(t *testing.T) {
	url := testdb.NewEmptyDatabaseURL(t)
	ctx := context.Background()
	holder, err := pgx.Connect(ctx, url)
	require.NoError(t, err)
	defer holder.Close(ctx)
	_, err = holder.Exec(ctx, "SELECT pg_advisory_lock($1)", lock.DefaultLockID)
	require.NoError(t, err)

	done := make(chan error, 1)
	go func() { done <- migrate.UpWithLockRetry(ctx, url, time.Second, 30, platformmigrations.Module) }()

	select {
	case err := <-done:
		t.Fatalf("migration ran while another runner held the lock: %v", err)
	case <-time.After(300 * time.Millisecond):
	}

	_, err = holder.Exec(ctx, "SELECT pg_advisory_unlock($1)", lock.DefaultLockID)
	require.NoError(t, err)
	select {
	case err := <-done:
		require.NoError(t, err)
	case <-time.After(10 * time.Second):
		t.Fatal("migration did not run after the lock was released")
	}
}

func TestConcurrentMigrationRunsAllSucceedAndApplyEachMigrationOnce(t *testing.T) {
	url := testdb.NewEmptyDatabaseURL(t)
	const runners = 6

	var wg sync.WaitGroup
	errs := make([]error, runners)
	for i := range runners {
		wg.Add(1)
		go func() {
			defer wg.Done()
			errs[i] = migrate.UpWithLockRetry(context.Background(), url, time.Second, 30, platformmigrations.Module)
		}()
	}
	wg.Wait()

	for _, err := range errs {
		require.NoError(t, err)
	}
	pool, err := pgxpool.New(context.Background(), url)
	require.NoError(t, err)
	defer pool.Close()
	var applied int
	require.NoError(t, pool.QueryRow(context.Background(),
		`SELECT count(*) FROM goose_platform WHERE version_id > 0`).Scan(&applied))
	require.Equal(t, 3, applied)
}

func TestRunningMigrationsAgainChangesNothing(t *testing.T) {
	url := testdb.NewEmptyDatabaseURL(t)

	require.NoError(t, migrate.Up(context.Background(), url, platformmigrations.Module))
	require.NoError(t, migrate.Up(context.Background(), url, platformmigrations.Module))
}

func TestLockRetrySettingsThatGooseCannotHonourAreRejected(t *testing.T) {
	cases := map[string]struct {
		period   time.Duration
		attempts uint64
		message  string
	}{
		"below one second":   {500 * time.Millisecond, 1, "whole number of seconds"},
		"fractional seconds": {1500 * time.Millisecond, 1, "whole number of seconds"},
		"no attempts":        {time.Second, 0, "at least one"},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			err := migrate.UpWithLockRetry(context.Background(), "postgres://unused", c.period, c.attempts)

			require.ErrorContains(t, err, c.message)
		})
	}
}
