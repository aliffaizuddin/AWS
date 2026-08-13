package metadata

import "errors"

var (
	ErrBucketNotFound      = errors.New("metadata: bucket not found")
	ErrBucketAlreadyExists = errors.New("metadata: bucket already exists")
	ErrObjectNotFound      = errors.New("metadata: object not found")
)
