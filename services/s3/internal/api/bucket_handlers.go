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
	HasObjects(ctx context.Context, bucket string) (bool, error)
}

type BucketHandlers struct {
	Buckets BucketRepo
	Objects ObjectLister
}

func (h *BucketHandlers) Create(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("bucket")

	// Narrow, deliberate slice of bucket-name validation: reject the empty
	// string and the "healthz" name specifically, since a bucket literally
	// named "healthz" would collide with the /healthz route (the literal
	// path pattern takes precedence over the "/{bucket}" wildcard for HEAD
	// requests to that exact path). Full AWS bucket-naming rules (length,
	// DNS-safe charset, etc.) are a separate, later decision.
	if name == "" || name == "healthz" {
		WriteS3Error(w, ErrInvalidBucketName, name)
		return
	}

	err := h.Buckets.Create(r.Context(), name)
	if errors.Is(err, metadata.ErrBucketAlreadyExists) {
		WriteS3Error(w, ErrBucketAlreadyExists, name)
		return
	}
	if err != nil {
		writeInternalError(w, name, err)
		return
	}
	w.WriteHeader(http.StatusOK)
}

func (h *BucketHandlers) List(w http.ResponseWriter, r *http.Request) {
	buckets, err := h.Buckets.List(r.Context())
	if err != nil {
		writeInternalError(w, "", err)
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

	hasObjects, err := h.Objects.HasObjects(r.Context(), name)
	if err != nil {
		writeInternalError(w, name, err)
		return
	}
	if hasObjects {
		WriteS3Error(w, ErrBucketNotEmpty, name)
		return
	}

	err = h.Buckets.Delete(r.Context(), name)
	if errors.Is(err, metadata.ErrBucketNotFound) {
		WriteS3Error(w, ErrNoSuchBucket, name)
		return
	}
	if err != nil {
		writeInternalError(w, name, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
