package api

import (
	"context"
	"net/http"
)

// NewHealthzHandler returns a handler that reports 200 if ping succeeds
// (typically a DB ping) and 503 otherwise.
func NewHealthzHandler(ping func(ctx context.Context) error) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := ping(r.Context()); err != nil {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
	}
}
