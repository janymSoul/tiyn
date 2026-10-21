package config_test

import (
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"tiyn/internal/platform/config"
)

func env(overrides map[string]string) func(string) string {
	values := map[string]string{
		"DATABASE_URL":      "postgres://x",
		"JWT_SECRET":        "0123456789abcdef0123456789abcdef",
		"GOOGLE_CLIENT_IDS": "android.apps.googleusercontent.com",
	}
	for key, value := range overrides {
		values[key] = value
	}
	return func(key string) string { return values[key] }
}

func without(keys ...string) func(string) string {
	base := env(nil)
	removed := map[string]bool{}
	for _, key := range keys {
		removed[key] = true
	}
	return func(key string) string {
		if removed[key] {
			return ""
		}
		return base(key)
	}
}

func TestLoadAppliesDefaults(t *testing.T) {
	cfg, err := config.Load(env(nil))

	require.NoError(t, err)
	require.Equal(t, ":8080", cfg.HTTPAddr)
	require.Equal(t, time.Second, cfg.RelayInterval)
	require.Equal(t, 50, cfg.RelayBatchSize)
	require.Equal(t, 5*time.Minute, cfg.RelayMaxBackoff)
	require.Equal(t, 10*time.Second, cfg.HandlerTimeout)
	require.Equal(t, 20, cfg.DBMaxConns)
	require.Equal(t, 1, cfg.HandlerMaxRetries)
	require.Equal(t, 7*24*time.Hour, cfg.JWTTTL)
}

func TestLoadRequiresTheDatabaseTheSecretAndTheGoogleClientIDs(t *testing.T) {
	_, err := config.Load(without("DATABASE_URL", "JWT_SECRET", "GOOGLE_CLIENT_IDS"))

	require.ErrorContains(t, err, "DATABASE_URL is required")
	require.ErrorContains(t, err, "JWT_SECRET is required")
	require.ErrorContains(t, err, "GOOGLE_CLIENT_IDS is required")
}

func TestLoadSplitsAndTrimsTheGoogleClientIDsDroppingEmptyEntries(t *testing.T) {
	cfg, err := config.Load(env(map[string]string{"GOOGLE_CLIENT_IDS": " android.id , ios.id,, web.id ,"}))

	require.NoError(t, err)
	require.Equal(t, []string{"android.id", "ios.id", "web.id"}, cfg.GoogleClientIDs)
}

func TestLoadTreatsAListOfOnlySeparatorsAsMissing(t *testing.T) {
	_, err := config.Load(env(map[string]string{"GOOGLE_CLIENT_IDS": " , ,"}))

	require.ErrorContains(t, err, "GOOGLE_CLIENT_IDS is required")
}

func TestLoadReportsEveryInvalidValue(t *testing.T) {
	_, err := config.Load(env(map[string]string{"RELAY_INTERVAL": "soon", "RELAY_BATCH_SIZE": "-1", "JWT_TTL": "forever"}))

	require.ErrorContains(t, err, "RELAY_INTERVAL")
	require.ErrorContains(t, err, "RELAY_BATCH_SIZE")
	require.ErrorContains(t, err, "JWT_TTL")
}

func TestLoadAcceptsZeroHandlerRetries(t *testing.T) {
	cfg, err := config.Load(env(map[string]string{"HANDLER_MAX_RETRIES": "0"}))

	require.NoError(t, err)
	require.Equal(t, 0, cfg.HandlerMaxRetries)
}

func TestLoadRejectsNegativeHandlerRetries(t *testing.T) {
	_, err := config.Load(env(map[string]string{"HANDLER_MAX_RETRIES": "-1"}))

	require.ErrorContains(t, err, "HANDLER_MAX_RETRIES")
}

func TestLoadRejectsZeroForPositiveIntegers(t *testing.T) {
	_, err := config.Load(env(map[string]string{"RELAY_BATCH_SIZE": "0"}))

	require.ErrorContains(t, err, "RELAY_BATCH_SIZE")
}

func TestLoadRequiresEnoughConnectionsForTheRelayAndItsHandlers(t *testing.T) {
	for _, tooFew := range []string{"0", "1", "3"} {
		_, err := config.Load(env(map[string]string{"DB_MAX_CONNS": tooFew}))
		require.ErrorContains(t, err, "DB_MAX_CONNS", tooFew)
	}

	cfg, err := config.Load(env(map[string]string{"DB_MAX_CONNS": "4"}))

	require.NoError(t, err)
	require.Equal(t, 4, cfg.DBMaxConns)
}

func TestLoadReadsRetryIntervalsAndRejectsMaxBelowInitial(t *testing.T) {
	cfg, err := config.Load(env(nil))
	require.NoError(t, err)
	require.Equal(t, 100*time.Millisecond, cfg.RetryInitial)
	require.Equal(t, 500*time.Millisecond, cfg.RetryMax)

	_, err = config.Load(env(map[string]string{"HANDLER_RETRY_INITIAL": "10s", "HANDLER_RETRY_MAX": "1s"}))
	require.ErrorContains(t, err, "HANDLER_RETRY_MAX")
}
