package api_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/api"
	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// fakeBucketRepo and fakeObjectLister are in-memory test doubles — see
// fakes_test.go for their shared definitions used across handler tests.

func TestBucketHandlers_Create(t *testing.T) {
	buckets := newFakeBucketRepo()
	h := api.BucketHandlers{Buckets: buckets, Objects: newFakeObjectLister()}

	req := httptest.NewRequest(http.MethodPut, "/my-bucket", nil)
	req.SetPathValue("bucket", "my-bucket")
	rec := httptest.NewRecorder()

	h.Create(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	_, err := buckets.Get(context.Background(), "my-bucket")
	require.NoError(t, err)
}

func TestBucketHandlers_Create_Duplicate(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "dup"))
	h := api.BucketHandlers{Buckets: buckets, Objects: newFakeObjectLister()}

	req := httptest.NewRequest(http.MethodPut, "/dup", nil)
	req.SetPathValue("bucket", "dup")
	rec := httptest.NewRecorder()

	h.Create(rec, req)

	assert.Equal(t, http.StatusConflict, rec.Code)
}

func TestBucketHandlers_List(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "a"))
	require.NoError(t, buckets.Create(context.Background(), "b"))
	h := api.BucketHandlers{Buckets: buckets, Objects: newFakeObjectLister()}

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	rec := httptest.NewRecorder()

	h.List(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), "<Name>a</Name>")
	assert.Contains(t, rec.Body.String(), "<Name>b</Name>")
}

func TestBucketHandlers_Head_Exists(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "exists"))
	h := api.BucketHandlers{Buckets: buckets, Objects: newFakeObjectLister()}

	req := httptest.NewRequest(http.MethodHead, "/exists", nil)
	req.SetPathValue("bucket", "exists")
	rec := httptest.NewRecorder()

	h.Head(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestBucketHandlers_Head_Missing(t *testing.T) {
	h := api.BucketHandlers{Buckets: newFakeBucketRepo(), Objects: newFakeObjectLister()}

	req := httptest.NewRequest(http.MethodHead, "/missing", nil)
	req.SetPathValue("bucket", "missing")
	rec := httptest.NewRecorder()

	h.Head(rec, req)

	assert.Equal(t, http.StatusNotFound, rec.Code)
}

func TestBucketHandlers_Delete_Empty(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "empty"))
	h := api.BucketHandlers{Buckets: buckets, Objects: newFakeObjectLister()}

	req := httptest.NewRequest(http.MethodDelete, "/empty", nil)
	req.SetPathValue("bucket", "empty")
	rec := httptest.NewRecorder()

	h.Delete(rec, req)

	assert.Equal(t, http.StatusNoContent, rec.Code)
}

func TestBucketHandlers_Delete_NotEmpty(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "full"))
	objects := newFakeObjectLister()
	objects.add("full", metadata.Object{BucketName: "full", Key: "k"})
	h := api.BucketHandlers{Buckets: buckets, Objects: objects}

	req := httptest.NewRequest(http.MethodDelete, "/full", nil)
	req.SetPathValue("bucket", "full")
	rec := httptest.NewRecorder()

	h.Delete(rec, req)

	assert.Equal(t, http.StatusConflict, rec.Code)
}

func TestBucketHandlers_Delete_Missing(t *testing.T) {
	h := api.BucketHandlers{Buckets: newFakeBucketRepo(), Objects: newFakeObjectLister()}

	req := httptest.NewRequest(http.MethodDelete, "/missing", nil)
	req.SetPathValue("bucket", "missing")
	rec := httptest.NewRecorder()

	h.Delete(rec, req)

	assert.Equal(t, http.StatusNotFound, rec.Code)
}
