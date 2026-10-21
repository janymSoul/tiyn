package testdb

import (
	"context"
	"fmt"
	"net/url"
	"os"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/postgres"

	"tiyn/internal/platform/migrate"
	platformmigrations "tiyn/internal/platform/migrations"
)

const (
	postgresImage = "postgres:16"
	templateName  = "tiyn_template"
	startTimeout  = 3 * time.Minute
)

var (
	adminURL    string
	unavailable string
	cloneCount  atomic.Int64
)

func Main(m *testing.M, modules ...migrate.Module) {
	if os.Getenv("TIYN_SKIP_FUNCTIONAL") != "" {
		unavailable = "TIYN_SKIP_FUNCTIONAL is set"
		os.Exit(m.Run())
	}

	ctx, cancel := context.WithTimeout(context.Background(), startTimeout)
	container, err := postgres.Run(ctx, postgresImage,
		postgres.WithDatabase("postgres"),
		postgres.WithUsername("test"),
		postgres.WithPassword("test"),
		postgres.BasicWaitStrategies(),
	)
	cancel()
	if err != nil {
		unavailable = fmt.Sprintf("postgres container unavailable: %v", err)
		os.Exit(m.Run())
	}

	code := 1
	defer func() {
		_ = testcontainers.TerminateContainer(container)
		os.Exit(code)
	}()

	if err := prepare(container, modules); err != nil {
		unavailable = fmt.Sprintf("prepare template database: %v", err)
	}
	code = m.Run()
}

func prepare(container *postgres.PostgresContainer, modules []migrate.Module) error {
	ctx := context.Background()
	var err error
	adminURL, err = container.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		return err
	}
	admin, err := pgxpool.New(ctx, adminURL)
	if err != nil {
		return err
	}
	defer admin.Close()
	if _, err := admin.Exec(ctx, "CREATE DATABASE "+templateName); err != nil {
		return err
	}
	all := append([]migrate.Module{platformmigrations.Module}, modules...)
	return migrate.Up(ctx, databaseURL(templateName), all...)
}

func New(t *testing.T) *pgxpool.Pool {
	t.Helper()
	if unavailable != "" {
		t.Skip(unavailable)
	}
	ctx := context.Background()
	name := fmt.Sprintf("test_%d", cloneCount.Add(1))

	admin, err := pgxpool.New(ctx, adminURL)
	if err != nil {
		t.Fatalf("connect admin: %v", err)
	}
	defer admin.Close()
	if _, err := admin.Exec(ctx, fmt.Sprintf("CREATE DATABASE %s TEMPLATE %s", name, templateName)); err != nil {
		t.Fatalf("clone template: %v", err)
	}

	pool, err := pgxpool.New(ctx, databaseURL(name))
	if err != nil {
		t.Fatalf("connect clone: %v", err)
	}
	t.Cleanup(func() {
		pool.Close()
		cleanup, err := pgxpool.New(ctx, adminURL)
		if err != nil {
			return
		}
		defer cleanup.Close()
		_, _ = cleanup.Exec(ctx, fmt.Sprintf("DROP DATABASE %s WITH (FORCE)", name))
	})
	return pool
}

func databaseURL(database string) string {
	parsed, err := url.Parse(adminURL)
	if err != nil {
		panic(err)
	}
	parsed.Path = "/" + database
	return parsed.String()
}
