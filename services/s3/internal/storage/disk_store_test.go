package storage_test

import (
	"bytes"
	"context"
	"errors"
	"io"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/storage"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// failingReader is a reader that returns an error after reading n bytes.
type failingReader struct {
	data []byte
	pos  int
	fail int
}

func newFailingReader(data []byte, failAfter int) *failingReader {
	return &failingReader{data: data, fail: failAfter}
}

func (fr *failingReader) Read(p []byte) (int, error) {
	if fr.pos >= fr.fail {
		return 0, errors.New("simulated read failure")
	}
	n := copy(p, fr.data[fr.pos:])
	if fr.pos+n > fr.fail {
		n = fr.fail - fr.pos
	}
	fr.pos += n
	if fr.pos < len(fr.data) && fr.pos < fr.fail {
		return n, nil
	}
	if fr.pos >= fr.fail {
		return n, errors.New("simulated read failure")
	}
	return n, io.EOF
}

func TestDiskStore_PutGetRoundTrip(t *testing.T) {
	dir := t.TempDir()
	store, err := storage.NewDiskStore(dir)
	require.NoError(t, err)

	id := uuid.New()
	ctx := context.Background()
	want := []byte("hello world")

	require.NoError(t, store.Put(ctx, id, bytes.NewReader(want)))

	r, err := store.Get(ctx, id)
	require.NoError(t, err)
	defer r.Close()

	got, err := io.ReadAll(r)
	require.NoError(t, err)
	assert.Equal(t, want, got)
}

func TestDiskStore_Put_Overwrites(t *testing.T) {
	dir := t.TempDir()
	store, err := storage.NewDiskStore(dir)
	require.NoError(t, err)

	id := uuid.New()
	ctx := context.Background()
	require.NoError(t, store.Put(ctx, id, bytes.NewReader([]byte("first"))))
	require.NoError(t, store.Put(ctx, id, bytes.NewReader([]byte("second"))))

	r, err := store.Get(ctx, id)
	require.NoError(t, err)
	defer r.Close()
	got, err := io.ReadAll(r)
	require.NoError(t, err)
	assert.Equal(t, []byte("second"), got)
}

func TestDiskStore_GetMissing_ReturnsErrNotFound(t *testing.T) {
	dir := t.TempDir()
	store, err := storage.NewDiskStore(dir)
	require.NoError(t, err)

	_, err = store.Get(context.Background(), uuid.New())
	assert.ErrorIs(t, err, storage.ErrNotFound)
}

func TestDiskStore_Delete(t *testing.T) {
	dir := t.TempDir()
	store, err := storage.NewDiskStore(dir)
	require.NoError(t, err)

	id := uuid.New()
	ctx := context.Background()
	require.NoError(t, store.Put(ctx, id, bytes.NewReader([]byte("x"))))
	require.NoError(t, store.Delete(ctx, id))

	_, err = store.Get(ctx, id)
	assert.ErrorIs(t, err, storage.ErrNotFound)
}

func TestDiskStore_DeleteMissing_ReturnsErrNotFound(t *testing.T) {
	dir := t.TempDir()
	store, err := storage.NewDiskStore(dir)
	require.NoError(t, err)

	err = store.Delete(context.Background(), uuid.New())
	assert.ErrorIs(t, err, storage.ErrNotFound)
}

func TestDiskStore_Put_FailedWriteLeavesNoPartialFile(t *testing.T) {
	dir := t.TempDir()
	store, err := storage.NewDiskStore(dir)
	require.NoError(t, err)

	id := uuid.New()
	ctx := context.Background()
	data := []byte("hello world")

	// Try to Put with a reader that fails partway through.
	// Fail after 5 bytes to ensure we write some data before the failure.
	failingReader := newFailingReader(data, 5)
	err = store.Put(ctx, id, failingReader)
	require.Error(t, err) // Put should fail

	// Verify that Get returns ErrNotFound, not a partial file.
	// If the atomic write worked correctly, no file should exist under the blob's id.
	_, err = store.Get(ctx, id)
	assert.ErrorIs(t, err, storage.ErrNotFound)
}
