package task

import (
	"embed"

	"tiyn/internal/platform/migrate"
)

//go:embed store/migrations/*.sql
var migrationFiles embed.FS

var Migrations = migrate.Module{Name: "task", FS: migrationFiles, Dir: "store/migrations"}
