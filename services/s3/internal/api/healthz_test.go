package api_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/api"
	"github.com/stretchr/testify/assert"
)

func TestHealthz_Healthy(t *testing.T) {
	h := api.NewHealthzHandler(func(ctx context.Context) error { return nil })
	req := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	rec := httptest.NewRecorder()
	h(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestHealthz_Unhealthy(t *testing.T) {
	h := api.NewHealthzHandler(func(ctx context.Context) error { return errors.New("db down") })
	req := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	rec := httptest.NewRecorder()
	h(rec, req)
	assert.Equal(t, http.StatusServiceUnavailable, rec.Code)
}
