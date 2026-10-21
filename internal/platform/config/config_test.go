package config_test

import (
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"tiyn/internal/platform/config"
)

func env(values map[string]string) func(string) string {
	return func(key string) string { return values[key] }
}

func TestLoadAppliesDefaults(t *testing.T) {
	cfg, err := config.Load(env(map[string]string{"DATABASE_URL": "postgres://x"}))

	require.NoError(t, err)
	require.Equal(t, ":8080", cfg.HTTPAddr)
	require.Equal(t, time.Second, cfg.RelayInterval)
	require.Equal(t, 50, cfg.RelayBatchSize)
	require.Equal(t, 5, cfg.RelayMaxAttempts)
}

func TestLoadRequiresDatabaseURL(t *testing.T) {
	_, err := config.Load(env(nil))

	require.ErrorContains(t, err, "DATABASE_URL is required")
}

func TestLoadReportsEveryInvalidValue(t *testing.T) {
	_, err := config.Load(env(map[string]string{
		"DATABASE_URL":     "postgres://x",
		"RELAY_INTERVAL":   "soon",
		"RELAY_BATCH_SIZE": "-1",
	}))

	require.ErrorContains(t, err, "RELAY_INTERVAL")
	require.ErrorContains(t, err, "RELAY_BATCH_SIZE")
}
