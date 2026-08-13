package metadata_test

import (
	"context"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/jmoiron/sqlx"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
)

func newTestDB(t *testing.T) *sqlx.DB {
	t.Helper()
	ctx := context.Background()

	container, err := tcpostgres.Run(ctx, "postgres:16-alpine",
		tcpostgres.WithDatabase("s3test"),
		tcpostgres.WithUsername("s3test"),
		tcpostgres.WithPassword("s3test"),
		tcpostgres.BasicWaitStrategies(),
	)
	if err != nil {
		t.Fatalf("start postgres container: %v", err)
	}
	t.Cleanup(func() { _ = container.Terminate(ctx) })

	dsn, err := container.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		t.Fatalf("get connection string: %v", err)
	}

	db, err := metadata.Connect(ctx, dsn)
	if err != nil {
		t.Fatalf("connect + migrate: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	return db
}
