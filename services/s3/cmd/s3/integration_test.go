package main_test

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/api"
	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/aliffaizuddin/AWS/services/s3/internal/storage"
	"github.com/stretchr/testify/require"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
)

// TestS3Service_EndToEnd exercises the full stack — real Postgres (via
// testcontainers), a real DiskStore (temp dir), and the real HTTP router —
// through the create-bucket -> put-object -> get-object -> delete-object ->
// delete-bucket lifecycle.
func TestS3Service_EndToEnd(t *testing.T) {
	ctx := context.Background()

	container, err := tcpostgres.Run(ctx, "postgres:16-alpine",
		tcpostgres.WithDatabase("s3e2e"),
		tcpostgres.WithUsername("s3e2e"),
		tcpostgres.WithPassword("s3e2e"),
		tcpostgres.BasicWaitStrategies(),
	)
	require.NoError(t, err)
	t.Cleanup(func() { _ = container.Terminate(ctx) })

	dsn, err := container.ConnectionString(ctx, "sslmode=disable")
	require.NoError(t, err)

	db, err := metadata.Connect(ctx, dsn)
	require.NoError(t, err)
	t.Cleanup(func() { _ = db.Close() })

	store, err := storage.NewDiskStore(t.TempDir())
	require.NoError(t, err)

	buckets := metadata.NewBucketRepo(db)
	objects := metadata.NewObjectRepo(db)

	bucketH := &api.BucketHandlers{Buckets: buckets, Objects: objects}
	objectH := &api.ObjectHandlers{Buckets: bucketH.Buckets, Objects: objects, Store: store}
	router := api.NewRouter(bucketH, objectH, api.NewHealthzHandler(func(ctx context.Context) error {
		return db.PingContext(ctx)
	}))

	srv := httptest.NewServer(router)
	defer srv.Close()

	client := srv.Client()

	// Create bucket.
	req, _ := http.NewRequest(http.MethodPut, srv.URL+"/e2e-bucket", nil)
	resp, err := client.Do(req)
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	resp.Body.Close()

	// Put object.
	req, _ = http.NewRequest(http.MethodPut, srv.URL+"/e2e-bucket/hello.txt", strings.NewReader("hello world"))
	req.Header.Set("Content-Type", "text/plain")
	resp, err = client.Do(req)
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	resp.Body.Close()

	// Get object back.
	resp, err = client.Get(srv.URL + "/e2e-bucket/hello.txt")
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	body, err := io.ReadAll(resp.Body)
	require.NoError(t, err)
	resp.Body.Close()
	require.Equal(t, "hello world", string(body))
	require.Equal(t, "text/plain", resp.Header.Get("Content-Type"))

	// Delete object.
	req, _ = http.NewRequest(http.MethodDelete, srv.URL+"/e2e-bucket/hello.txt", nil)
	resp, err = client.Do(req)
	require.NoError(t, err)
	require.Equal(t, http.StatusNoContent, resp.StatusCode)
	resp.Body.Close()

	// Delete (now-empty) bucket.
	req, _ = http.NewRequest(http.MethodDelete, srv.URL+"/e2e-bucket", nil)
	resp, err = client.Do(req)
	require.NoError(t, err)
	require.Equal(t, http.StatusNoContent, resp.StatusCode)
	resp.Body.Close()
}
