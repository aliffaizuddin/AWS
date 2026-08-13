package api_test

import (
	"encoding/xml"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/aliffaizuddin/AWS/services/s3/internal/api"
	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestWriteXML_ListAllMyBucketsResult(t *testing.T) {
	buckets := []metadata.Bucket{
		{Name: "alpha", CreatedAt: time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)},
		{Name: "beta", CreatedAt: time.Date(2026, 2, 1, 0, 0, 0, 0, time.UTC)},
	}
	result := api.NewListAllMyBucketsResult(buckets)

	rec := httptest.NewRecorder()
	api.WriteXML(rec, 200, result)

	assert.Equal(t, "application/xml", rec.Header().Get("Content-Type"))
	assert.Equal(t, 200, rec.Code)

	var parsed api.ListAllMyBucketsResult
	require.NoError(t, xml.Unmarshal(rec.Body.Bytes(), &parsed))
	require.Len(t, parsed.Buckets.Bucket, 2)
	assert.Equal(t, "alpha", parsed.Buckets.Bucket[0].Name)
	assert.Equal(t, "beta", parsed.Buckets.Bucket[1].Name)
}
