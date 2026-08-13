package api

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/aliffaizuddin/AWS/services/s3/internal/storage"
	"github.com/google/uuid"
)

// ObjectRepo is the subset of metadata.ObjectRepo's methods the object
// handlers need.
type ObjectRepo interface {
	Put(ctx context.Context, obj metadata.Object) error
	Get(ctx context.Context, bucket, key string) (*metadata.Object, error)
	Delete(ctx context.Context, bucket, key string) error
}

type ObjectHandlers struct {
	Buckets BucketRepo
	Objects ObjectRepo
	Store   storage.Store
}

func (h *ObjectHandlers) Put(w http.ResponseWriter, r *http.Request) {
	bucket := r.PathValue("bucket")
	key := r.PathValue("key")

	if _, err := h.Buckets.Get(r.Context(), bucket); errors.Is(err, metadata.ErrBucketNotFound) {
		WriteS3Error(w, ErrNoSuchBucket, bucket)
		return
	} else if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, bucket)
		return
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, key)
		return
	}

	sum := md5.Sum(body)
	etag := hex.EncodeToString(sum[:])
	storageID := uuid.New()

	if err := h.Store.Put(r.Context(), storageID, bytes.NewReader(body)); err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, key)
		return
	}

	contentType := r.Header.Get("Content-Type")
	if contentType == "" {
		contentType = "application/octet-stream"
	}

	obj := metadata.Object{
		BucketName:  bucket,
		Key:         key,
		ContentType: contentType,
		SizeBytes:   int64(len(body)),
		ETag:        etag,
		StorageID:   storageID,
	}
	if err := h.Objects.Put(r.Context(), obj); err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, key)
		return
	}

	w.Header().Set("ETag", etag)
	w.WriteHeader(http.StatusOK)
}

func (h *ObjectHandlers) Get(w http.ResponseWriter, r *http.Request) {
	bucket := r.PathValue("bucket")
	key := r.PathValue("key")

	obj, err := h.Objects.Get(r.Context(), bucket, key)
	if errors.Is(err, metadata.ErrObjectNotFound) {
		WriteS3Error(w, ErrNoSuchKey, key)
		return
	}
	if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, key)
		return
	}

	blob, err := h.Store.Get(r.Context(), obj.StorageID)
	if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, key)
		return
	}
	defer blob.Close()

	writeObjectHeaders(w, obj)
	w.WriteHeader(http.StatusOK)
	_, _ = io.Copy(w, blob)
}

func (h *ObjectHandlers) Head(w http.ResponseWriter, r *http.Request) {
	bucket := r.PathValue("bucket")
	key := r.PathValue("key")

	obj, err := h.Objects.Get(r.Context(), bucket, key)
	if errors.Is(err, metadata.ErrObjectNotFound) {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	writeObjectHeaders(w, obj)
	w.WriteHeader(http.StatusOK)
}

// Delete is idempotent, matching real S3: it always returns 204, whether
// or not the key existed.
func (h *ObjectHandlers) Delete(w http.ResponseWriter, r *http.Request) {
	bucket := r.PathValue("bucket")
	key := r.PathValue("key")

	obj, err := h.Objects.Get(r.Context(), bucket, key)
	if err == nil {
		_ = h.Store.Delete(r.Context(), obj.StorageID)
		_ = h.Objects.Delete(r.Context(), bucket, key)
	}

	w.WriteHeader(http.StatusNoContent)
}

func writeObjectHeaders(w http.ResponseWriter, obj *metadata.Object) {
	w.Header().Set("Content-Type", obj.ContentType)
	w.Header().Set("Content-Length", strconv.FormatInt(obj.SizeBytes, 10))
	w.Header().Set("ETag", obj.ETag)
	w.Header().Set("Last-Modified", obj.CreatedAt.UTC().Format(http.TimeFormat))
}
