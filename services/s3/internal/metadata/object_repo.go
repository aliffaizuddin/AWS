package metadata

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/jmoiron/sqlx"
)

// ObjectRepo provides CRUD access to the objects table.
type ObjectRepo struct {
	db *sqlx.DB
}

func NewObjectRepo(db *sqlx.DB) *ObjectRepo {
	return &ObjectRepo{db: db}
}

// Put inserts obj, or overwrites the existing row for the same
// (bucket_name, key) — Phase 1 has no versioning, so a second PUT to the
// same key replaces it.
func (r *ObjectRepo) Put(ctx context.Context, obj Object) error {
	_, err := r.db.ExecContext(ctx, `
		INSERT INTO objects (bucket_name, key, content_type, size_bytes, etag, storage_id)
		VALUES ($1, $2, $3, $4, $5, $6)
		ON CONFLICT (bucket_name, key) DO UPDATE SET
			content_type = EXCLUDED.content_type,
			size_bytes   = EXCLUDED.size_bytes,
			etag         = EXCLUDED.etag,
			storage_id   = EXCLUDED.storage_id,
			created_at   = now()
	`, obj.BucketName, obj.Key, obj.ContentType, obj.SizeBytes, obj.ETag, obj.StorageID)
	if err != nil {
		return fmt.Errorf("metadata: put object %s/%s: %w", obj.BucketName, obj.Key, err)
	}
	return nil
}

func (r *ObjectRepo) Get(ctx context.Context, bucket, key string) (*Object, error) {
	var o Object
	err := r.db.GetContext(ctx, &o, `
		SELECT bucket_name, key, content_type, size_bytes, etag, storage_id, created_at
		FROM objects WHERE bucket_name = $1 AND key = $2
	`, bucket, key)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrObjectNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("metadata: get object %s/%s: %w", bucket, key, err)
	}
	return &o, nil
}

func (r *ObjectRepo) Delete(ctx context.Context, bucket, key string) error {
	res, err := r.db.ExecContext(ctx, `DELETE FROM objects WHERE bucket_name = $1 AND key = $2`, bucket, key)
	if err != nil {
		return fmt.Errorf("metadata: delete object %s/%s: %w", bucket, key, err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("metadata: delete object %s/%s: %w", bucket, key, err)
	}
	if n == 0 {
		return ErrObjectNotFound
	}
	return nil
}

func (r *ObjectRepo) ListByBucket(ctx context.Context, bucket string) ([]Object, error) {
	var objs []Object
	err := r.db.SelectContext(ctx, &objs, `
		SELECT bucket_name, key, content_type, size_bytes, etag, storage_id, created_at
		FROM objects WHERE bucket_name = $1 ORDER BY key
	`, bucket)
	if err != nil {
		return nil, fmt.Errorf("metadata: list objects in %s: %w", bucket, err)
	}
	return objs, nil
}
