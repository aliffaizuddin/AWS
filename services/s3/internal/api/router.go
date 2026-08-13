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
	mux.HandleFunc("DELETE /{bucket}", bucketH.Delete)

	// The bucket HEAD route is registered without a method restriction and
	// dispatches on r.Method itself, rather than as "HEAD /{bucket}".
	// net/http.ServeMux's pattern-conflict check treats "GET /healthz"
	// (which implicitly also serves HEAD requests) and a method-restricted
	// "HEAD /{bucket}" wildcard as ambiguous: one pattern is more general by
	// method, the other by path, and neither dominates — so registering
	// both panics at startup. Making this route method-agnostic (it
	// dispatches HEAD internally, else 404) makes it consistently more
	// general than "GET /healthz" along both axes, which is unambiguous
	// and does not conflict.
	mux.HandleFunc("/{bucket}", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodHead {
			bucketH.Head(w, r)
			return
		}
		http.NotFound(w, r)
	})

	mux.HandleFunc("PUT /{bucket}/{key...}", objectH.Put)
	mux.HandleFunc("GET /{bucket}/{key...}", objectH.Get)
	mux.HandleFunc("HEAD /{bucket}/{key...}", objectH.Head)
	mux.HandleFunc("DELETE /{bucket}/{key...}", objectH.Delete)

	return mux
}
