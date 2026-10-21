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
	require.Equal(t, 10*time.Second, cfg.HandlerTimeout)
	require.Equal(t, 20, cfg.DBMaxConns)
	require.Equal(t, 3, cfg.HandlerMaxRetries)
	require.Equal(t, 5*time.Minute, cfg.RelayMaxBackoff)
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

func TestLoadAcceptsZeroHandlerRetries(t *testing.T) {
	cfg, err := config.Load(env(map[string]string{"DATABASE_URL": "postgres://x", "HANDLER_MAX_RETRIES": "0"}))

	require.NoError(t, err)
	require.Equal(t, 0, cfg.HandlerMaxRetries)
}

func TestLoadRejectsNegativeHandlerRetries(t *testing.T) {
	_, err := config.Load(env(map[string]string{"DATABASE_URL": "postgres://x", "HANDLER_MAX_RETRIES": "-1"}))

	require.ErrorContains(t, err, "HANDLER_MAX_RETRIES")
}

func TestLoadRejectsZeroForPositiveIntegers(t *testing.T) {
	_, err := config.Load(env(map[string]string{"DATABASE_URL": "postgres://x", "RELAY_BATCH_SIZE": "0"}))

	require.ErrorContains(t, err, "RELAY_BATCH_SIZE")
}

func TestLoadRequiresEnoughConnectionsForTheRelayAndItsHandlers(t *testing.T) {
	for _, tooFew := range []string{"0", "1", "3"} {
		_, err := config.Load(env(map[string]string{"DATABASE_URL": "postgres://x", "DB_MAX_CONNS": tooFew}))
		require.ErrorContains(t, err, "DB_MAX_CONNS", tooFew)
	}

	cfg, err := config.Load(env(map[string]string{"DATABASE_URL": "postgres://x", "DB_MAX_CONNS": "4"}))

	require.NoError(t, err)
	require.Equal(t, 4, cfg.DBMaxConns)
}

func TestLoadReadsRetryIntervalsAndRejectsMaxBelowInitial(t *testing.T) {
	cfg, err := config.Load(env(map[string]string{"DATABASE_URL": "postgres://x"}))
	require.NoError(t, err)
	require.Equal(t, 200*time.Millisecond, cfg.RetryInitial)
	require.Equal(t, 5*time.Second, cfg.RetryMax)

	_, err = config.Load(env(map[string]string{"DATABASE_URL": "postgres://x", "HANDLER_RETRY_INITIAL": "10s", "HANDLER_RETRY_MAX": "1s"}))
	require.ErrorContains(t, err, "HANDLER_RETRY_MAX")
}
