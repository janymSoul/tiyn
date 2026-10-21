package migrate

import (
	"context"
	"database/sql"
	"fmt"
	"io/fs"

	"github.com/pressly/goose/v3"
	"github.com/pressly/goose/v3/lock"

	_ "github.com/jackc/pgx/v5/stdlib"
)

type Module struct {
	Name string
	FS   fs.FS
	Dir  string
}

func Up(ctx context.Context, databaseURL string, modules ...Module) error {
	db, err := sql.Open("pgx", databaseURL)
	if err != nil {
		return fmt.Errorf("open database: %w", err)
	}
	defer db.Close()

	for _, module := range modules {
		if err := upModule(ctx, db, module); err != nil {
			return fmt.Errorf("migrate %s: %w", module.Name, err)
		}
	}
	return nil
}

func upModule(ctx context.Context, db *sql.DB, module Module) error {
	migrations, err := fs.Sub(module.FS, module.Dir)
	if err != nil {
		return err
	}
	locker, err := lock.NewPostgresSessionLocker()
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
