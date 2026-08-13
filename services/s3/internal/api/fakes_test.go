package api_test

import (
	"context"
	"sort"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
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
