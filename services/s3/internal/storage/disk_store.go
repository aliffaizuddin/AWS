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
	// Write to a temporary file first, then rename atomically to the final path.
	// This ensures that Get/Delete never observe a partial or corrupted file.
	tmpPath := d.path(id) + ".tmp"
	f, err := os.Create(tmpPath)
	if err != nil {
		return fmt.Errorf("storage: create tmp %s: %w", id, err)
	}
	if _, err := io.Copy(f, r); err != nil {
		f.Close()
		// Clean up the temp file on write failure to avoid leaving partial files.
		os.Remove(tmpPath)
		return fmt.Errorf("storage: write %s: %w", id, err)
	}
	// Flush to disk before closing and renaming, so a crash between the
	// write and the rename can't leave a renamed-but-not-flushed file.
	if err := f.Sync(); err != nil {
		f.Close()
		os.Remove(tmpPath)
		return fmt.Errorf("storage: sync %s: %w", id, err)
	}
	if err := f.Close(); err != nil {
		os.Remove(tmpPath)
		return fmt.Errorf("storage: close tmp %s: %w", id, err)
	}
	// Atomically move the temp file to the final path.
	if err := os.Rename(tmpPath, d.path(id)); err != nil {
		os.Remove(tmpPath)
		return fmt.Errorf("storage: rename %s: %w", id, err)
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
