package config

import (
	"errors"
	"fmt"
	"strconv"
	"time"
)

type Config struct {
	DatabaseURL       string
	HTTPAddr          string
	ShutdownTimeout   time.Duration
	RelayInterval     time.Duration
	RelayBatchSize    int
	HandlerTimeout    time.Duration
	DBMaxConns        int
	RelayMaxBackoff   time.Duration
	HandlerMaxRetries int
}

func Load(getenv func(string) string) (Config, error) {
	var errs []error
	cfg := Config{
		DatabaseURL:       getenv("DATABASE_URL"),
		HTTPAddr:          stringOr(getenv("HTTP_ADDR"), ":8080"),
		ShutdownTimeout:   durationOr(getenv, "SHUTDOWN_TIMEOUT", 15*time.Second, &errs),
		RelayInterval:     durationOr(getenv, "RELAY_INTERVAL", time.Second, &errs),
		RelayBatchSize:    intAtLeast(getenv, "RELAY_BATCH_SIZE", 50, 1, &errs),
		HandlerTimeout:    durationOr(getenv, "HANDLER_TIMEOUT", 10*time.Second, &errs),
		DBMaxConns:        intAtLeast(getenv, "DB_MAX_CONNS", 20, 1, &errs),
		RelayMaxBackoff:   durationOr(getenv, "RELAY_MAX_BACKOFF", 5*time.Minute, &errs),
		HandlerMaxRetries: intAtLeast(getenv, "HANDLER_MAX_RETRIES", 3, 0, &errs),
	}
	if cfg.DatabaseURL == "" {
		errs = append(errs, errors.New("DATABASE_URL is required"))
	}
	if err := errors.Join(errs...); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

func stringOr(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

func durationOr(getenv func(string) string, key string, fallback time.Duration, errs *[]error) time.Duration {
	raw := getenv(key)
	if raw == "" {
		return fallback
	}
	value, err := time.ParseDuration(raw)
	if err != nil || value <= 0 {
		*errs = append(*errs, fmt.Errorf("%s must be a positive duration, got %q", key, raw))
		return fallback
	}
	return value
}

func intAtLeast(getenv func(string) string, key string, fallback, minimum int, errs *[]error) int {
	raw := getenv(key)
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < minimum {
		*errs = append(*errs, fmt.Errorf("%s must be an integer of at least %d, got %q", key, minimum, raw))
		return fallback
	}
	return value
}
