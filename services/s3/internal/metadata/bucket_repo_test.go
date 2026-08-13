package metadata_test

import (
	"context"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestBucketRepo_CreateGetDelete(t *testing.T) {
	db := newTestDB(t)
	repo := metadata.NewBucketRepo(db)
	ctx := context.Background()

	require.NoError(t, repo.Create(ctx, "my-bucket"))

	b, err := repo.Get(ctx, "my-bucket")
	require.NoError(t, err)
	assert.Equal(t, "my-bucket", b.Name)

	require.NoError(t, repo.Delete(ctx, "my-bucket"))

	_, err = repo.Get(ctx, "my-bucket")
	assert.ErrorIs(t, err, metadata.ErrBucketNotFound)
}

func TestBucketRepo_Create_Duplicate_ReturnsErrBucketAlreadyExists(t *testing.T) {
	db := newTestDB(t)
	repo := metadata.NewBucketRepo(db)
	ctx := context.Background()

	require.NoError(t, repo.Create(ctx, "dup-bucket"))
	err := repo.Create(ctx, "dup-bucket")
	assert.ErrorIs(t, err, metadata.ErrBucketAlreadyExists)
}

func TestBucketRepo_Delete_Missing_ReturnsErrBucketNotFound(t *testing.T) {
	db := newTestDB(t)
	repo := metadata.NewBucketRepo(db)

	err := repo.Delete(context.Background(), "never-existed")
	assert.ErrorIs(t, err, metadata.ErrBucketNotFound)
}

func TestBucketRepo_List(t *testing.T) {
	db := newTestDB(t)
	repo := metadata.NewBucketRepo(db)
	ctx := context.Background()

	require.NoError(t, repo.Create(ctx, "bucket-a"))
	require.NoError(t, repo.Create(ctx, "bucket-b"))

	buckets, err := repo.List(ctx)
	require.NoError(t, err)
	names := []string{buckets[0].Name, buckets[1].Name}
	assert.ElementsMatch(t, []string{"bucket-a", "bucket-b"}, names)
}
