package scheduler

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"
)

type Ticker interface {
	Tick(ctx context.Context, now time.Time) error
}

func Loop(ctx context.Context, interval time.Duration, ticker Ticker, logger *slog.Logger) {
	timer := time.NewTicker(interval)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-timer.C:
			if err := ticker.Tick(ctx, now); err != nil && ctx.Err() == nil {
				logger.Error("tick failed", "err", err)
			}
		}
	}
}

func RunOnce(ctx context.Context, now time.Time, tickers ...Ticker) error {
	var errs []error
	for _, ticker := range tickers {
		if err := ticker.Tick(ctx, now); err != nil {
			errs = append(errs, fmt.Errorf("%T: %w", ticker, err))
		}
	}
	return errors.Join(errs...)
}
