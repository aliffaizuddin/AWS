package main

import (
	"context"
	"log"
	"net/http"
	"os"

	"github.com/aliffaizuddin/AWS/services/s3/internal/api"
	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/aliffaizuddin/AWS/services/s3/internal/storage"
)

func main() {
	ctx := context.Background()

	dsn := requireEnv("S3_DB_DSN")
	dataDir := requireEnv("S3_DATA_DIR")
	listenAddr := envOrDefault("S3_LISTEN_ADDR", ":8080")

	db, err := metadata.Connect(ctx, dsn)
	if err != nil {
		log.Fatalf("connect to postgres: %v", err)
	}
	defer db.Close()

	store, err := storage.NewDiskStore(dataDir)
	if err != nil {
		log.Fatalf("init disk store: %v", err)
	}

	buckets := metadata.NewBucketRepo(db)
	objects := metadata.NewObjectRepo(db)

	bucketH := &api.BucketHandlers{Buckets: buckets, Objects: objects}
	objectH := &api.ObjectHandlers{Buckets: buckets, Objects: objects, Store: store}
	healthz := api.NewHealthzHandler(func(ctx context.Context) error {
		return db.PingContext(ctx)
	})

	router := api.NewRouter(bucketH, objectH, healthz)

	log.Printf("s3 service listening on %s", listenAddr)
	if err := http.ListenAndServe(listenAddr, router); err != nil {
		log.Fatalf("server error: %v", err)
	}
}

func requireEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		log.Fatalf("missing required env var %s", key)
	}
	return v
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
