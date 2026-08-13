package api

import (
	"context"
	"errors"
	"net/http"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
)

// BucketRepo is the subset of metadata.BucketRepo's methods the bucket
// handlers need — satisfied by *metadata.BucketRepo in production and by
// an in-memory fake in tests.
type BucketRepo interface {
	Create(ctx context.Context, name string) error
	Get(ctx context.Context, name string) (*metadata.Bucket, error)
	Delete(ctx context.Context, name string) error
	List(ctx context.Context) ([]metadata.Bucket, error)
}

// ObjectLister is the subset of metadata.ObjectRepo's methods the bucket
// handlers need (only to check emptiness before a bucket delete).
type ObjectLister interface {
	ListByBucket(ctx context.Context, bucket string) ([]metadata.Object, error)
}

type BucketHandlers struct {
	Buckets BucketRepo
	Objects ObjectLister
}

func (h *BucketHandlers) Create(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("bucket")
	err := h.Buckets.Create(r.Context(), name)
	if errors.Is(err, metadata.ErrBucketAlreadyExists) {
		WriteS3Error(w, ErrBucketAlreadyExists, name)
		return
	}
	if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, name)
		return
	}
	w.WriteHeader(http.StatusOK)
}

func (h *BucketHandlers) List(w http.ResponseWriter, r *http.Request) {
	buckets, err := h.Buckets.List(r.Context())
	if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, "")
		return
	}
	WriteXML(w, http.StatusOK, NewListAllMyBucketsResult(buckets))
}

func (h *BucketHandlers) Head(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("bucket")
	_, err := h.Buckets.Get(r.Context(), name)
	if errors.Is(err, metadata.ErrBucketNotFound) {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusOK)
}

func (h *BucketHandlers) Delete(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("bucket")

	objs, err := h.Objects.ListByBucket(r.Context(), name)
	if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, name)
		return
	}
	if len(objs) > 0 {
		WriteS3Error(w, ErrBucketNotEmpty, name)
		return
	}

	err = h.Buckets.Delete(r.Context(), name)
	if errors.Is(err, metadata.ErrBucketNotFound) {
		WriteS3Error(w, ErrNoSuchBucket, name)
		return
	}
	if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, name)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
