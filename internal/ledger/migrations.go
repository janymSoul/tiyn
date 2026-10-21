package ledger

import (
	"embed"

	"tiyn/internal/platform/migrate"
)

//go:embed store/migrations/*.sql
var migrationFiles embed.FS

var Migrations = migrate.Module{Name: "ledger", FS: migrationFiles, Dir: "store/migrations"}
