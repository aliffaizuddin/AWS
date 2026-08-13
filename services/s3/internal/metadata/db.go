package metadata

import (
	"context"
	"embed"
	"fmt"

	"github.com/golang-migrate/migrate/v4"
	"github.com/golang-migrate/migrate/v4/database/postgres"
	"github.com/golang-migrate/migrate/v4/source/iofs"
	"github.com/jmoiron/sqlx"

	_ "github.com/jackc/pgx/v5/stdlib" // registers the "pgx" sql driver
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

// Connect opens a Postgres connection pool at dsn and runs all pending
// migrations before returning.
func Connect(ctx context.Context, dsn string) (*sqlx.DB, error) {
	db, err := sqlx.ConnectContext(ctx, "pgx", dsn)
	if err != nil {
		return nil, fmt.Errorf("metadata: connect: %w", err)
	}

	if err := migrateUp(ctx, db); err != nil {
		db.Close()
		return nil, err
	}

	return db, nil
}

func migrateUp(ctx context.Context, db *sqlx.DB) error {
	src, err := iofs.New(migrationsFS, "migrations")
	if err != nil {
		return fmt.Errorf("metadata: load migrations: %w", err)
	}

	// Get a single dedicated connection from the pool for the migrator to
	// hold its advisory lock on, and use postgres.WithConnection (not
	// postgres.WithInstance) to build the driver from it. This matters:
	// postgres.WithInstance stores the *sql.DB you pass it and its Close()
	// unconditionally closes that *sql.DB too — since that would be our
	// whole pool (the one Connect returns to its caller), driver.Close()
	// would kill the pool out from under the caller. postgres.WithConnection
	// only holds the one *sql.Conn, so releasing it via conn.Close() (which
	// returns the connection to the pool, not the pool itself) is safe.
	conn, err := db.Conn(ctx)
	if err != nil {
		return fmt.Errorf("metadata: migration connection: %w", err)
	}
	defer conn.Close()

	driver, err := postgres.WithConnection(ctx, conn, &postgres.Config{})
	if err != nil {
		return fmt.Errorf("metadata: migration driver: %w", err)
	}

	m, err := migrate.NewWithInstance("iofs", src, "postgres", driver)
	if err != nil {
		return fmt.Errorf("metadata: init migrator: %w", err)
	}

	if err := m.Up(); err != nil && err != migrate.ErrNoChange {
		return fmt.Errorf("metadata: run migrations: %w", err)
	}
	return nil
}
