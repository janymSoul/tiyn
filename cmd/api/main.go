package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"golang.org/x/sync/errgroup"

	"tiyn/internal/platform/config"
	"tiyn/internal/platform/db"
	"tiyn/internal/platform/eventbus"
	"tiyn/internal/platform/httpx"
	"tiyn/internal/platform/outbox"
	"tiyn/internal/platform/scheduler"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(logger); err != nil {
		logger.Error("api stopped", "err", err)
		os.Exit(1)
	}
}

func run(logger *slog.Logger) error {
	cfg, err := config.Load(os.Getenv)
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	pool, err := db.NewPool(ctx, cfg.DatabaseURL, cfg.DBMaxConns)
	if err != nil {
		return err
	}
	defer pool.Close()
	store := db.NewStore(pool)

	bus := eventbus.New(eventbus.NewPostgresDeadLetters(pool), eventbus.Options{
		HandlerMaxRetries:    cfg.HandlerMaxRetries,
		HandlerTimeout:       cfg.HandlerTimeout,
		RetryInitialInterval: cfg.RetryInitial,
		RetryMaxInterval:     cfg.RetryMax,
	})

	relay := outbox.NewRelay(store, bus, outbox.RelayConfig{
		BatchSize:  cfg.RelayBatchSize,
		MaxBackoff: cfg.RelayMaxBackoff,
	}, logger)

	server := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           httpx.NewRouter(pool.Ping),
		ReadHeaderTimeout: 5 * time.Second,
	}

	group, groupCtx := errgroup.WithContext(ctx)
	group.Go(func() error {
		scheduler.Loop(groupCtx, cfg.RelayInterval, relay, logger)
		return nil
	})
	group.Go(func() error {
		logger.Info("http server listening", "addr", cfg.HTTPAddr)
		if err := server.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	})
	group.Go(func() error {
		<-groupCtx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
		defer cancel()
		return server.Shutdown(shutdownCtx)
	})
	return group.Wait()
}
