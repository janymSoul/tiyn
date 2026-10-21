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
	RelayMaxAttempts  int
	HandlerMaxRetries int
}

func Load(getenv func(string) string) (Config, error) {
	var errs []error
	cfg := Config{
		DatabaseURL:       getenv("DATABASE_URL"),
		HTTPAddr:          stringOr(getenv("HTTP_ADDR"), ":8080"),
		ShutdownTimeout:   durationOr(getenv, "SHUTDOWN_TIMEOUT", 15*time.Second, &errs),
		RelayInterval:     durationOr(getenv, "RELAY_INTERVAL", time.Second, &errs),
		RelayBatchSize:    intOr(getenv, "RELAY_BATCH_SIZE", 50, &errs),
		RelayMaxAttempts:  intOr(getenv, "RELAY_MAX_ATTEMPTS", 5, &errs),
		HandlerMaxRetries: intOr(getenv, "HANDLER_MAX_RETRIES", 3, &errs),
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

func intOr(getenv func(string) string, key string, fallback int, errs *[]error) int {
	raw := getenv(key)
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value <= 0 {
		*errs = append(*errs, fmt.Errorf("%s must be a positive integer, got %q", key, raw))
		return fallback
	}
	return value
}
