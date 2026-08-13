package api

import (
	"encoding/xml"
	"net/http"
	"time"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
)

// WriteXML marshals v as XML, writes the XML content-type header, and
// writes statusCode + the marshaled body to w.
func WriteXML(w http.ResponseWriter, statusCode int, v any) {
	body, err := xml.MarshalIndent(v, "", "  ")
	if err != nil {
		// Marshaling one of our own response structs should never fail;
		// if it does, there's nothing more specific to tell the caller.
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/xml")
	w.WriteHeader(statusCode)
	_, _ = w.Write([]byte(xml.Header))
	_, _ = w.Write(body)
}

// ListAllMyBucketsResult is the XML body for the ListBuckets operation.
type ListAllMyBucketsResult struct {
	XMLName xml.Name   `xml:"ListAllMyBucketsResult"`
	Owner   Owner      `xml:"Owner"`
	Buckets BucketsXML `xml:"Buckets"`
}

type Owner struct {
	ID          string `xml:"ID"`
	DisplayName string `xml:"DisplayName"`
}

type BucketsXML struct {
	Bucket []BucketXML `xml:"Bucket"`
}

type BucketXML struct {
	Name         string    `xml:"Name"`
	CreationDate time.Time `xml:"CreationDate"`
}

// NewListAllMyBucketsResult builds the XML response body from metadata
// rows. Owner is a fixed placeholder — Phase 1 has no IAM/identity wiring.
func NewListAllMyBucketsResult(buckets []metadata.Bucket) ListAllMyBucketsResult {
	xmlBuckets := make([]BucketXML, len(buckets))
	for i, b := range buckets {
		xmlBuckets[i] = BucketXML{Name: b.Name, CreationDate: b.CreatedAt}
	}
	return ListAllMyBucketsResult{
		Owner:   Owner{ID: "cloudlite", DisplayName: "cloudlite"},
		Buckets: BucketsXML{Bucket: xmlBuckets},
	}
}
