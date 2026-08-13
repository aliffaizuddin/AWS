package storage

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/google/uuid"
)

// DiskStore is a Store backed by flat files on local disk, one file per
// blob, named after its UUID.
type DiskStore struct {
	dataDir string
}

// NewDiskStore creates a DiskStore rooted at dataDir, creating the
// directory (and any parents) if it doesn't exist.
func NewDiskStore(dataDir string) (*DiskStore, error) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return nil, fmt.Errorf("storage: create data dir: %w", err)
	}
	return &DiskStore{dataDir: dataDir}, nil
}

func (d *DiskStore) path(id uuid.UUID) string {
	return filepath.Join(d.dataDir, id.String())
}

func (d *DiskStore) Put(ctx context.Context, id uuid.UUID, r io.Reader) error {
	f, err := os.Create(d.path(id))
	if err != nil {
		return fmt.Errorf("storage: create %s: %w", id, err)
	}
	defer f.Close()
	if _, err := io.Copy(f, r); err != nil {
		return fmt.Errorf("storage: write %s: %w", id, err)
	}
	return nil
}

func (d *DiskStore) Get(ctx context.Context, id uuid.UUID) (io.ReadCloser, error) {
	f, err := os.Open(d.path(id))
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("storage: open %s: %w", id, err)
	}
	return f, nil
}

func (d *DiskStore) Delete(ctx context.Context, id uuid.UUID) error {
	err := os.Remove(d.path(id))
	if errors.Is(err, os.ErrNotExist) {
		return ErrNotFound
	}
	if err != nil {
		return fmt.Errorf("storage: remove %s: %w", id, err)
	}
	return nil
}
