package api_test

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/api"
	"github.com/aliffaizuddin/AWS/services/s3/internal/storage"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestObjectHandlers_Put_Get_RoundTrip(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "b"))
	h := api.ObjectHandlers{Buckets: buckets, Objects: newFakeObjectRepo(), Store: newFakeStore()}

	putReq := httptest.NewRequest(http.MethodPut, "/b/k.txt", bytes.NewReader([]byte("hello")))
	putReq.SetPathValue("bucket", "b")
	putReq.SetPathValue("key", "k.txt")
	putReq.Header.Set("Content-Type", "text/plain")
	putRec := httptest.NewRecorder()
	h.Put(putRec, putReq)
	require.Equal(t, http.StatusOK, putRec.Code)
	require.NotEmpty(t, putRec.Header().Get("ETag"))

	getReq := httptest.NewRequest(http.MethodGet, "/b/k.txt", nil)
	getReq.SetPathValue("bucket", "b")
	getReq.SetPathValue("key", "k.txt")
	getRec := httptest.NewRecorder()
	h.Get(getRec, getReq)

	assert.Equal(t, http.StatusOK, getRec.Code)
	assert.Equal(t, "text/plain", getRec.Header().Get("Content-Type"))
	assert.Equal(t, "hello", getRec.Body.String())
}

func TestObjectHandlers_Put_Overwrite_DeletesOldBlob(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "b"))
	objects := newFakeObjectRepo()
	store := newFakeStore()
	h := api.ObjectHandlers{Buckets: buckets, Objects: objects, Store: store}

	firstReq := httptest.NewRequest(http.MethodPut, "/b/k.txt", bytes.NewReader([]byte("first")))
	firstReq.SetPathValue("bucket", "b")
	firstReq.SetPathValue("key", "k.txt")
	h.Put(httptest.NewRecorder(), firstReq)

	firstObj, err := objects.Get(context.Background(), "b", "k.txt")
	require.NoError(t, err)
	oldStorageID := firstObj.StorageID

	secondReq := httptest.NewRequest(http.MethodPut, "/b/k.txt", bytes.NewReader([]byte("second, longer body")))
	secondReq.SetPathValue("bucket", "b")
	secondReq.SetPathValue("key", "k.txt")
	rec := httptest.NewRecorder()
	h.Put(rec, secondReq)
	require.Equal(t, http.StatusOK, rec.Code)

	secondObj, err := objects.Get(context.Background(), "b", "k.txt")
	require.NoError(t, err)
	assert.NotEqual(t, oldStorageID, secondObj.StorageID)

	// The old blob must be gone (superseded); the new one must be present.
	_, err = store.Get(context.Background(), oldStorageID)
	assert.ErrorIs(t, err, storage.ErrNotFound)
	_, err = store.Get(context.Background(), secondObj.StorageID)
	assert.NoError(t, err)
}

func TestObjectHandlers_Put_ETagIsQuoted(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "b"))
	h := api.ObjectHandlers{Buckets: buckets, Objects: newFakeObjectRepo(), Store: newFakeStore()}

	req := httptest.NewRequest(http.MethodPut, "/b/k.txt", bytes.NewReader([]byte("hello")))
	req.SetPathValue("bucket", "b")
	req.SetPathValue("key", "k.txt")
	rec := httptest.NewRecorder()
	h.Put(rec, req)

	etag := rec.Header().Get("ETag")
	require.Len(t, etag, 34) // 32 hex chars + 2 surrounding quotes
	assert.True(t, strings.HasPrefix(etag, `"`) && strings.HasSuffix(etag, `"`))
}

func TestObjectHandlers_Put_MissingBucket(t *testing.T) {
	h := api.ObjectHandlers{Buckets: newFakeBucketRepo(), Objects: newFakeObjectRepo(), Store: newFakeStore()}

	req := httptest.NewRequest(http.MethodPut, "/missing/k.txt", bytes.NewReader([]byte("x")))
	req.SetPathValue("bucket", "missing")
	req.SetPathValue("key", "k.txt")
	rec := httptest.NewRecorder()
	h.Put(rec, req)

	assert.Equal(t, http.StatusNotFound, rec.Code)
}

func TestObjectHandlers_Get_MissingKey(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "b"))
	h := api.ObjectHandlers{Buckets: buckets, Objects: newFakeObjectRepo(), Store: newFakeStore()}

	req := httptest.NewRequest(http.MethodGet, "/b/missing.txt", nil)
	req.SetPathValue("bucket", "b")
	req.SetPathValue("key", "missing.txt")
	rec := httptest.NewRecorder()
	h.Get(rec, req)

	assert.Equal(t, http.StatusNotFound, rec.Code)
}

func TestObjectHandlers_Head(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "b"))
	h := api.ObjectHandlers{Buckets: buckets, Objects: newFakeObjectRepo(), Store: newFakeStore()}

	putReq := httptest.NewRequest(http.MethodPut, "/b/k.txt", bytes.NewReader([]byte("hello")))
	putReq.SetPathValue("bucket", "b")
	putReq.SetPathValue("key", "k.txt")
	h.Put(httptest.NewRecorder(), putReq)

	headReq := httptest.NewRequest(http.MethodHead, "/b/k.txt", nil)
	headReq.SetPathValue("bucket", "b")
	headReq.SetPathValue("key", "k.txt")
	headRec := httptest.NewRecorder()
	h.Head(headRec, headReq)

	assert.Equal(t, http.StatusOK, headRec.Code)
	assert.Empty(t, headRec.Body.String())
}

func TestObjectHandlers_Delete_AlwaysNoContent(t *testing.T) {
	h := api.ObjectHandlers{Buckets: newFakeBucketRepo(), Objects: newFakeObjectRepo(), Store: newFakeStore()}

	req := httptest.NewRequest(http.MethodDelete, "/b/never-existed.txt", nil)
	req.SetPathValue("bucket", "b")
	req.SetPathValue("key", "never-existed.txt")
	rec := httptest.NewRecorder()
	h.Delete(rec, req)

	assert.Equal(t, http.StatusNoContent, rec.Code)
}
