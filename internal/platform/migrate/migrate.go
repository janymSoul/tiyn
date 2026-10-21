package migrate

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"time"

	"github.com/pressly/goose/v3"
	"github.com/pressly/goose/v3/lock"

	_ "github.com/jackc/pgx/v5/stdlib"
)

type Module struct {
	Name string
	FS   fs.FS
	Dir  string
}

const (
	defaultLockRetryPeriod   = 5 * time.Second
	defaultLockRetryAttempts = 60
)

func Up(ctx context.Context, databaseURL string, modules ...Module) error {
	return UpWithLockRetry(ctx, databaseURL, defaultLockRetryPeriod, defaultLockRetryAttempts, modules...)
}

func UpWithLockRetry(ctx context.Context, databaseURL string, period time.Duration, attempts uint64, modules ...Module) error {
	if period < time.Second || period%time.Second != 0 {
		return fmt.Errorf("lock retry period must be a whole number of seconds, at least one, got %s", period)
	}
	if attempts == 0 {
		return errors.New("lock retry attempts must be at least one")
	}
	db, err := sql.Open("pgx", databaseURL)
	if err != nil {
		return fmt.Errorf("open database: %w", err)
	}
	defer db.Close()

	for _, module := range modules {
		if err := upModule(ctx, db, module, period, attempts); err != nil {
			return fmt.Errorf("migrate %s: %w", module.Name, err)
		}
	}
	return nil
}

func upModule(ctx context.Context, db *sql.DB, module Module, period time.Duration, attempts uint64) error {
	migrations, err := fs.Sub(module.FS, module.Dir)
	if err != nil {
		return err
	}
	locker, err := lock.NewPostgresSessionLocker(lock.WithLockTimeout(uint64(period/time.Second), attempts))
	if err != nil {
		return err
	}
	provider, err := goose.NewProvider(goose.DialectPostgres, db, migrations,
		goose.WithTableName("goose_"+module.Name), goose.WithSessionLocker(locker))
	if err != nil {
		return err
	}
	_, err = provider.Up(ctx)
	return err
}
