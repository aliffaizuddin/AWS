package api_test

import (
	"bytes"
	"context"
	"io"
	"sort"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/aliffaizuddin/AWS/services/s3/internal/storage"
	"github.com/google/uuid"
)

type fakeBucketRepo struct {
	buckets map[string]metadata.Bucket
}

func newFakeBucketRepo() *fakeBucketRepo {
	return &fakeBucketRepo{buckets: map[string]metadata.Bucket{}}
}

func (f *fakeBucketRepo) Create(ctx context.Context, name string) error {
	if _, ok := f.buckets[name]; ok {
		return metadata.ErrBucketAlreadyExists
	}
	f.buckets[name] = metadata.Bucket{Name: name}
	return nil
}

func (f *fakeBucketRepo) Get(ctx context.Context, name string) (*metadata.Bucket, error) {
	b, ok := f.buckets[name]
	if !ok {
		return nil, metadata.ErrBucketNotFound
	}
	return &b, nil
}

func (f *fakeBucketRepo) Delete(ctx context.Context, name string) error {
	if _, ok := f.buckets[name]; !ok {
		return metadata.ErrBucketNotFound
	}
	delete(f.buckets, name)
	return nil
}

func (f *fakeBucketRepo) List(ctx context.Context) ([]metadata.Bucket, error) {
	names := make([]string, 0, len(f.buckets))
	for name := range f.buckets {
		names = append(names, name)
	}
	sort.Strings(names)
	out := make([]metadata.Bucket, len(names))
	for i, name := range names {
		out[i] = f.buckets[name]
	}
	return out, nil
}

type fakeObjectLister struct {
	objects map[string][]metadata.Object
}

func newFakeObjectLister() *fakeObjectLister {
	return &fakeObjectLister{objects: map[string][]metadata.Object{}}
}

func (f *fakeObjectLister) add(bucket string, obj metadata.Object) {
	f.objects[bucket] = append(f.objects[bucket], obj)
}

func (f *fakeObjectLister) ListByBucket(ctx context.Context, bucket string) ([]metadata.Object, error) {
	return f.objects[bucket], nil
}

type fakeObjectRepo struct {
	objects map[string]metadata.Object // key: bucket+"/"+key
}

func newFakeObjectRepo() *fakeObjectRepo {
	return &fakeObjectRepo{objects: map[string]metadata.Object{}}
}

func (f *fakeObjectRepo) key(bucket, key string) string { return bucket + "/" + key }

func (f *fakeObjectRepo) Put(ctx context.Context, obj metadata.Object) error {
	f.objects[f.key(obj.BucketName, obj.Key)] = obj
	return nil
}

func (f *fakeObjectRepo) Get(ctx context.Context, bucket, key string) (*metadata.Object, error) {
	o, ok := f.objects[f.key(bucket, key)]
	if !ok {
		return nil, metadata.ErrObjectNotFound
	}
	return &o, nil
}

func (f *fakeObjectRepo) Delete(ctx context.Context, bucket, key string) error {
	delete(f.objects, f.key(bucket, key))
	return nil
}

type fakeStore struct {
	blobs map[string][]byte
}

func newFakeStore() *fakeStore {
	return &fakeStore{blobs: map[string][]byte{}}
}

func (f *fakeStore) Put(ctx context.Context, id uuid.UUID, r io.Reader) error {
	b, err := io.ReadAll(r)
	if err != nil {
		return err
	}
	f.blobs[id.String()] = b
	return nil
}

func (f *fakeStore) Get(ctx context.Context, id uuid.UUID) (io.ReadCloser, error) {
	b, ok := f.blobs[id.String()]
	if !ok {
		return nil, storage.ErrNotFound
	}
	return io.NopCloser(bytes.NewReader(b)), nil
}

func (f *fakeStore) Delete(ctx context.Context, id uuid.UUID) error {
	delete(f.blobs, id.String())
	return nil
}
