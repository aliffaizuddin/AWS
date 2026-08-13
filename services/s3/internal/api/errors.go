package api

import (
	"encoding/xml"
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
)

// StatusFor maps an S3Error to the HTTP status code it's written with.
func StatusFor(e S3Error) int {
	switch e.Code {
	case ErrNoSuchBucket.Code, ErrNoSuchKey.Code:
		return http.StatusNotFound
	case ErrBucketAlreadyExists.Code, ErrBucketNotEmpty.Code:
		return http.StatusConflict
	default:
		return http.StatusInternalServerError
	}
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
