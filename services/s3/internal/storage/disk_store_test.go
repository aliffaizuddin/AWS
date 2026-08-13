package storage_test

import (
	"bytes"
	"context"
	"io"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/storage"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

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
