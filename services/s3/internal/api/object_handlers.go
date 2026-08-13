package api

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/aliffaizuddin/AWS/services/s3/internal/storage"
	"github.com/google/uuid"
)

// maxObjectSize caps a single PUT's body size. Phase 1 has no auth, so an
// unbounded read of the request body could OOM the process; 100 MiB is a
// reasonable single-PUT cap for now — multipart upload (Phase 4) is the
// real path for larger objects.
const maxObjectSize = 100 << 20 // 100 MiB

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
		writeInternalError(w, bucket, err)
		return
	}

	// Look up the existing object (if any) for this key before writing the
	// new blob, so that once the new metadata row is committed we can clean
	// up the old blob it used to point at. A new-key PUT (ErrObjectNotFound)
	// is the normal case, not an error.
	existing, err := h.Objects.Get(r.Context(), bucket, key)
	if err != nil && !errors.Is(err, metadata.ErrObjectNotFound) {
		writeInternalError(w, key, err)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxObjectSize)
	body, err := io.ReadAll(r.Body)
	if err != nil {
		writeInternalError(w, key, err)
		return
	}

	sum := md5.Sum(body)
	etag := hex.EncodeToString(sum[:])
	storageID := uuid.New()

	if err := h.Store.Put(r.Context(), storageID, bytes.NewReader(body)); err != nil {
		writeInternalError(w, key, err)
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
		// The blob is already durably written but no metadata row points at
		// it now — it's orphaned. No good rollback story here (the write
		// already succeeded), so just make it visible in logs.
		log.Printf("s3: put object %s/%s: blob %s written but metadata upsert failed, blob is orphaned: %v", bucket, key, storageID, err)
		writeInternalError(w, key, err)
		return
	}

	// The metadata now points at the new blob. If this was an overwrite of
	// an existing key, the old blob is no longer referenced by anything —
	// delete it. Best-effort: the new object is already correctly stored
	// and served, so a failure here shouldn't fail the request.
	if existing != nil {
		if err := h.Store.Delete(r.Context(), existing.StorageID); err != nil {
			log.Printf("s3: put object %s/%s: failed to delete superseded blob %s: %v", bucket, key, existing.StorageID, err)
		}
	}

	w.Header().Set("ETag", fmt.Sprintf("%q", etag))
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
		writeInternalError(w, key, err)
		return
	}

	blob, err := h.Store.Get(r.Context(), obj.StorageID)
	if err != nil {
		writeInternalError(w, key, err)
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
//
// Order matters here: delete the metadata row first, then the blob. If the
// metadata delete fails after a blob delete succeeded, the row would
// survive pointing at a blob that no longer exists — every subsequent GET
// on that key would 500 forever, and the bucket could never be deleted (it
// would still count as non-empty). Deleting metadata first means the worst
// case on a blob-delete failure is a harmless orphaned blob, not a
// corrupted row.
func (h *ObjectHandlers) Delete(w http.ResponseWriter, r *http.Request) {
	bucket := r.PathValue("bucket")
	key := r.PathValue("key")

	obj, err := h.Objects.Get(r.Context(), bucket, key)
	if err == nil {
		if err := h.Objects.Delete(r.Context(), bucket, key); err != nil {
			log.Printf("s3: delete object %s/%s: metadata delete failed, leaving blob %s in place: %v", bucket, key, obj.StorageID, err)
		} else if err := h.Store.Delete(r.Context(), obj.StorageID); err != nil {
			log.Printf("s3: delete object %s/%s: blob %s delete failed after metadata delete, blob is orphaned: %v", bucket, key, obj.StorageID, err)
		}
	}

	w.WriteHeader(http.StatusNoContent)
}

func writeObjectHeaders(w http.ResponseWriter, obj *metadata.Object) {
	w.Header().Set("Content-Type", obj.ContentType)
	w.Header().Set("Content-Length", strconv.FormatInt(obj.SizeBytes, 10))
	w.Header().Set("ETag", fmt.Sprintf("%q", obj.ETag))
	w.Header().Set("Last-Modified", obj.CreatedAt.UTC().Format(http.TimeFormat))
}
