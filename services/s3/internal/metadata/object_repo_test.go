package metadata_test

import (
	"context"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestObjectRepo_PutGetDelete(t *testing.T) {
	db := newTestDB(t)
	buckets := metadata.NewBucketRepo(db)
	objects := metadata.NewObjectRepo(db)
	ctx := context.Background()

	require.NoError(t, buckets.Create(ctx, "obj-bucket"))

	storageID := uuid.New()
	obj := metadata.Object{
		BucketName:  "obj-bucket",
		Key:         "hello.txt",
		ContentType: "text/plain",
		SizeBytes:   11,
		ETag:        "abc123",
		StorageID:   storageID,
	}
	require.NoError(t, objects.Put(ctx, obj))

	got, err := objects.Get(ctx, "obj-bucket", "hello.txt")
	require.NoError(t, err)
	assert.Equal(t, "text/plain", got.ContentType)
	assert.Equal(t, storageID, got.StorageID)

	require.NoError(t, objects.Delete(ctx, "obj-bucket", "hello.txt"))
	_, err = objects.Get(ctx, "obj-bucket", "hello.txt")
	assert.ErrorIs(t, err, metadata.ErrObjectNotFound)
}

func TestObjectRepo_Put_OverwritesExistingKey(t *testing.T) {
	db := newTestDB(t)
	buckets := metadata.NewBucketRepo(db)
	objects := metadata.NewObjectRepo(db)
	ctx := context.Background()
	require.NoError(t, buckets.Create(ctx, "overwrite-bucket"))

	first := metadata.Object{BucketName: "overwrite-bucket", Key: "k", ContentType: "text/plain", SizeBytes: 1, ETag: "e1", StorageID: uuid.New()}
	second := metadata.Object{BucketName: "overwrite-bucket", Key: "k", ContentType: "application/json", SizeBytes: 2, ETag: "e2", StorageID: uuid.New()}

	require.NoError(t, objects.Put(ctx, first))
	require.NoError(t, objects.Put(ctx, second))

	got, err := objects.Get(ctx, "overwrite-bucket", "k")
	require.NoError(t, err)
	assert.Equal(t, "application/json", got.ContentType)
	assert.Equal(t, "e2", got.ETag)
}

func TestObjectRepo_Get_Missing_ReturnsErrObjectNotFound(t *testing.T) {
	db := newTestDB(t)
	buckets := metadata.NewBucketRepo(db)
	objects := metadata.NewObjectRepo(db)
	ctx := context.Background()
	require.NoError(t, buckets.Create(ctx, "empty-bucket"))

	_, err := objects.Get(ctx, "empty-bucket", "missing")
	assert.ErrorIs(t, err, metadata.ErrObjectNotFound)
}

func TestObjectRepo_Delete_Missing_ReturnsErrObjectNotFound(t *testing.T) {
	db := newTestDB(t)
	buckets := metadata.NewBucketRepo(db)
	objects := metadata.NewObjectRepo(db)
	ctx := context.Background()
	require.NoError(t, buckets.Create(ctx, "empty-bucket-2"))

	err := objects.Delete(ctx, "empty-bucket-2", "missing")
	assert.ErrorIs(t, err, metadata.ErrObjectNotFound)
}

func TestObjectRepo_ListByBucket(t *testing.T) {
	db := newTestDB(t)
	buckets := metadata.NewBucketRepo(db)
	objects := metadata.NewObjectRepo(db)
	ctx := context.Background()
	require.NoError(t, buckets.Create(ctx, "list-bucket"))

	require.NoError(t, objects.Put(ctx, metadata.Object{BucketName: "list-bucket", Key: "a", ContentType: "text/plain", SizeBytes: 1, ETag: "e", StorageID: uuid.New()}))
	require.NoError(t, objects.Put(ctx, metadata.Object{BucketName: "list-bucket", Key: "b", ContentType: "text/plain", SizeBytes: 1, ETag: "e", StorageID: uuid.New()}))

	list, err := objects.ListByBucket(ctx, "list-bucket")
	require.NoError(t, err)
	assert.Len(t, list, 2)
}
