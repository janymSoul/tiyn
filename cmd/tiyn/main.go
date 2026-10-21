package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"tiyn/internal/identity"
	"tiyn/internal/ledger"
	"tiyn/internal/notification"
	"tiyn/internal/platform/config"
	"tiyn/internal/platform/migrate"
	platformmigrations "tiyn/internal/platform/migrations"
	"tiyn/internal/platform/scheduler"
	"tiyn/internal/task"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(logger, os.Args[1:]); err != nil {
		logger.Error("tiyn failed", "err", err)
		os.Exit(1)
	}
}

func run(logger *slog.Logger, args []string) error {
	if len(args) != 1 {
		return fmt.Errorf("usage: tiyn <migrate|tick>")
	}
	cfg, err := config.Load(os.Getenv)
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	switch args[0] {
	case "migrate":
		return migrate.Up(ctx, cfg.DatabaseURL, migrationModules()...)
	case "tick":
		return tick(ctx, logger)
	default:
		return fmt.Errorf("unknown command %q, usage: tiyn <migrate|tick>", args[0])
	}
}

func migrationModules() []migrate.Module {
	return []migrate.Module{
		platformmigrations.Module,
		identity.Migrations,
		task.Migrations,
		ledger.Migrations,
		notification.Migrations,
	}
}

func tick(ctx context.Context, logger *slog.Logger) error {
	tickers := []scheduler.Ticker{}
	if len(tickers) == 0 {
		logger.Warn("no tickers registered, nothing to do")
		return nil
	}
	return scheduler.RunOnce(ctx, time.Now(), tickers...)
}
