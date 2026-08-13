package api

import (
	"encoding/xml"
	"log"
	"net/http"

	"github.com/google/uuid"
)

// S3Error is an AWS-shaped error: a stable Code (what SDKs branch on) and
// a human-readable Message.
type S3Error struct {
	Code    string
	Message string
}

var (
	ErrNoSuchBucket        = S3Error{Code: "NoSuchBucket", Message: "The specified bucket does not exist"}
	ErrNoSuchKey           = S3Error{Code: "NoSuchKey", Message: "The specified key does not exist"}
	ErrBucketAlreadyExists = S3Error{Code: "BucketAlreadyExists", Message: "The requested bucket name is not available"}
	ErrBucketNotEmpty      = S3Error{Code: "BucketNotEmpty", Message: "The bucket you tried to delete is not empty"}
	ErrInvalidBucketName   = S3Error{Code: "InvalidBucketName", Message: "The specified bucket name is not valid"}
	ErrMethodNotAllowed    = S3Error{Code: "MethodNotAllowed", Message: "The specified method is not allowed against this resource"}
	ErrGenericNotFound     = S3Error{Code: "NotFound", Message: "The specified resource does not exist"}
)

// StatusFor maps an S3Error to the HTTP status code it's written with.
func StatusFor(e S3Error) int {
	switch e.Code {
	case ErrNoSuchBucket.Code, ErrNoSuchKey.Code, ErrGenericNotFound.Code:
		return http.StatusNotFound
	case ErrBucketAlreadyExists.Code, ErrBucketNotEmpty.Code:
		return http.StatusConflict
	case ErrInvalidBucketName.Code:
		return http.StatusBadRequest
	case ErrMethodNotAllowed.Code:
		return http.StatusMethodNotAllowed
	default:
		return http.StatusInternalServerError
	}
}

// writeInternalError logs the underlying error server-side (so an operator
// can diagnose it) and writes a generic, non-leaky InternalError body to
// the client — err.Error() can contain SQL fragments, column names, or
// absolute filesystem paths, which Phase 1 (no auth) would otherwise
// disclose to any caller.
func writeInternalError(w http.ResponseWriter, resource string, err error) {
	log.Printf("s3: internal error on %q: %v", resource, err)
	WriteS3Error(w, S3Error{Code: "InternalError", Message: "We encountered an internal error. Please try again."}, resource)
}

type errorXML struct {
	XMLName   xml.Name `xml:"Error"`
	Code      string   `xml:"Code"`
	Message   string   `xml:"Message"`
	Resource  string   `xml:"Resource"`
	RequestID string   `xml:"RequestId"`
}

// WriteS3Error writes e as an AWS-shaped XML error body, with resource
// identifying the bucket or key the error is about.
func WriteS3Error(w http.ResponseWriter, e S3Error, resource string) {
	WriteXML(w, StatusFor(e), errorXML{
		Code:      e.Code,
		Message:   e.Message,
		Resource:  resource,
		RequestID: uuid.NewString(),
	})
}
