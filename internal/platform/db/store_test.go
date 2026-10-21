package db_test

import (
	"context"
	"errors"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/stretchr/testify/require"

	"tiyn/internal/platform/db"
	"tiyn/internal/platform/testdb"
)

func TestMain(m *testing.M) {
	testdb.Main(m)
}

func TestExecTxCommitsOnSuccess(t *testing.T) {
	pool := testdb.New(t)
	store := db.NewStore(pool)
	ctx := context.Background()
	_, err := pool.Exec(ctx, `CREATE TABLE tx_probe (id INT PRIMARY KEY)`)
	require.NoError(t, err)

	err = store.ExecTx(ctx, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `INSERT INTO tx_probe VALUES (1)`)
		return err
	})

	require.NoError(t, err)
	require.Equal(t, 1, count(t, pool, "tx_probe"))
}

func TestExecTxRollsBackOnError(t *testing.T) {
	pool := testdb.New(t)
	store := db.NewStore(pool)
	ctx := context.Background()
	_, err := pool.Exec(ctx, `CREATE TABLE tx_probe (id INT PRIMARY KEY)`)
	require.NoError(t, err)
	boom := errors.New("boom")

	err = store.ExecTx(ctx, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `INSERT INTO tx_probe VALUES (1)`); err != nil {
			return err
		}
		return boom
	})

	require.ErrorIs(t, err, boom)
	require.Equal(t, 0, count(t, pool, "tx_probe"))
}

func TestIsUniqueViolation(t *testing.T) {
	pool := testdb.New(t)
	ctx := context.Background()
	_, err := pool.Exec(ctx, `CREATE TABLE tx_probe (id INT PRIMARY KEY)`)
	require.NoError(t, err)
	_, err = pool.Exec(ctx, `INSERT INTO tx_probe VALUES (1)`)
	require.NoError(t, err)

	_, err = pool.Exec(ctx, `INSERT INTO tx_probe VALUES (1)`)

	require.True(t, db.IsUniqueViolation(err))
	require.False(t, db.IsUniqueViolation(errors.New("other")))
}

func count(t *testing.T, pool interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}, table string) int {
	t.Helper()
	var n int
	require.NoError(t, pool.QueryRow(context.Background(), "SELECT count(*) FROM "+table).Scan(&n))
	return n
}

func TestExecTxRollsBackAndReleasesTheConnectionWhenFnPanics(t *testing.T) {
	pool := testdb.New(t)
	store := db.NewStore(pool)
	ctx := context.Background()
	_, err := pool.Exec(ctx, `CREATE TABLE tx_probe (id INT PRIMARY KEY)`)
	require.NoError(t, err)

	require.Panics(t, func() {
		_ = store.ExecTx(ctx, func(tx pgx.Tx) error {
			_, _ = tx.Exec(ctx, `INSERT INTO tx_probe VALUES (1)`)
			panic("boom")
		})
	})

	require.Equal(t, 0, count(t, pool, "tx_probe"))
	require.Equal(t, int32(0), pool.Stat().AcquiredConns())
}

func TestExecTxRollsBackWhenItsContextIsCancelledMidTransaction(t *testing.T) {
	pool := testdb.New(t)
	store := db.NewStore(pool)
	ctx, cancel := context.WithCancel(context.Background())
	_, err := pool.Exec(context.Background(), `CREATE TABLE tx_probe (id INT PRIMARY KEY)`)
	require.NoError(t, err)

	err = store.ExecTx(ctx, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `INSERT INTO tx_probe VALUES (1)`); err != nil {
			return err
		}
		cancel()
		return ctx.Err()
	})

	require.ErrorIs(t, err, context.Canceled)
	require.Equal(t, 0, count(t, pool, "tx_probe"))
	require.Equal(t, int32(0), pool.Stat().AcquiredConns())
}

func TestUniqueViolationConstraintNamesTheViolatedConstraint(t *testing.T) {
	pool := testdb.New(t)
	ctx := context.Background()
	_, err := pool.Exec(ctx, `CREATE TABLE tx_probe (id INT PRIMARY KEY, email TEXT CONSTRAINT tx_probe_email_key UNIQUE)`)
	require.NoError(t, err)
	_, err = pool.Exec(ctx, `INSERT INTO tx_probe VALUES (1, 'a')`)
	require.NoError(t, err)

	_, err = pool.Exec(ctx, `INSERT INTO tx_probe VALUES (2, 'a')`)
	name, ok := db.UniqueViolationConstraint(err)
	require.True(t, ok)
	require.Equal(t, "tx_probe_email_key", name)

	_, err = pool.Exec(ctx, `INSERT INTO tx_probe VALUES (1, 'b')`)
	name, ok = db.UniqueViolationConstraint(err)
	require.True(t, ok)
	require.Equal(t, "tx_probe_pkey", name)

	_, ok = db.UniqueViolationConstraint(errors.New("other"))
	require.False(t, ok)
}
