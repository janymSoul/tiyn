package migrations

import (
	"embed"

	"tiyn/internal/platform/migrate"
)

//go:embed *.sql
var files embed.FS

var Module = migrate.Module{Name: "platform", FS: files, Dir: "."}
