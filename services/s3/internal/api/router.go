package api

import "net/http"

// NewRouter builds the full S3-clone HTTP route table. Path-style
// addressing only: bucket and object operations are distinguished by
// path shape ("/{bucket}" vs "/{bucket}/{key...}"), not by host header.
func NewRouter(bucketH *BucketHandlers, objectH *ObjectHandlers, healthz http.HandlerFunc) *http.ServeMux {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", healthz)

	mux.HandleFunc("GET /{$}", bucketH.List)
	mux.HandleFunc("PUT /{bucket}", bucketH.Create)
	mux.HandleFunc("HEAD /{bucket}", bucketH.Head)
	mux.HandleFunc("DELETE /{bucket}", bucketH.Delete)

	mux.HandleFunc("PUT /{bucket}/{key...}", objectH.Put)
	mux.HandleFunc("GET /{bucket}/{key...}", objectH.Get)
	mux.HandleFunc("HEAD /{bucket}/{key...}", objectH.Head)
	mux.HandleFunc("DELETE /{bucket}/{key...}", objectH.Delete)

	return mux
}
