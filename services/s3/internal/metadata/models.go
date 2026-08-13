package metadata

import (
	"time"

	"github.com/google/uuid"
)

// Bucket is a single row in the buckets table.
type Bucket struct {
	Name      string    `db:"name"`
	CreatedAt time.Time `db:"created_at"`
}

// Object is a single row in the objects table — Phase 1 keeps one row per
// (bucket_name, key), i.e. no version history yet.
type Object struct {
	BucketName  string    `db:"bucket_name"`
	Key         string    `db:"key"`
	ContentType string    `db:"content_type"`
	SizeBytes   int64     `db:"size_bytes"`
	ETag        string    `db:"etag"`
	StorageID   uuid.UUID `db:"storage_id"`
	CreatedAt   time.Time `db:"created_at"`
}
