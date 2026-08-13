package metadata

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jmoiron/sqlx"
)

// BucketRepo provides CRUD access to the buckets table.
type BucketRepo struct {
	db *sqlx.DB
}

// NewBucketRepo constructs a BucketRepo over an already-connected,
// already-migrated db (see Connect).
func NewBucketRepo(db *sqlx.DB) *BucketRepo {
	return &BucketRepo{db: db}
}

func (r *BucketRepo) Create(ctx context.Context, name string) error {
	_, err := r.db.ExecContext(ctx, `INSERT INTO buckets (name) VALUES ($1)`, name)
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" { // unique_violation
			return ErrBucketAlreadyExists
		}
		return fmt.Errorf("metadata: create bucket %s: %w", name, err)
	}
	return nil
}

func (r *BucketRepo) Get(ctx context.Context, name string) (*Bucket, error) {
	var b Bucket
	err := r.db.GetContext(ctx, &b, `SELECT name, created_at FROM buckets WHERE name = $1`, name)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrBucketNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("metadata: get bucket %s: %w", name, err)
	}
	return &b, nil
}

func (r *BucketRepo) Delete(ctx context.Context, name string) error {
	res, err := r.db.ExecContext(ctx, `DELETE FROM buckets WHERE name = $1`, name)
	if err != nil {
		return fmt.Errorf("metadata: delete bucket %s: %w", name, err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("metadata: delete bucket %s: %w", name, err)
	}
	if n == 0 {
		return ErrBucketNotFound
	}
	return nil
}

func (r *BucketRepo) List(ctx context.Context) ([]Bucket, error) {
	var buckets []Bucket
	err := r.db.SelectContext(ctx, &buckets, `SELECT name, created_at FROM buckets ORDER BY name`)
	if err != nil {
		return nil, fmt.Errorf("metadata: list buckets: %w", err)
	}
	return buckets, nil
}
