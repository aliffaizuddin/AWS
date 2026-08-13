package storage

import (
	"context"
	"errors"
	"io"

	"github.com/google/uuid"
)

// ErrNotFound is returned by Store.Get and Store.Delete when the given id
// has no corresponding blob.
var ErrNotFound = errors.New("storage: object not found")

// Store is a content-agnostic blob store, keyed by a caller-supplied UUID.
// It has no knowledge of buckets or keys — that mapping lives in the
// metadata package.
type Store interface {
	Put(ctx context.Context, id uuid.UUID, r io.Reader) error
	Get(ctx context.Context, id uuid.UUID) (io.ReadCloser, error)
	Delete(ctx context.Context, id uuid.UUID) error
}
