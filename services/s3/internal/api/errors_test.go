package api_test

import (
	"encoding/xml"
	"net/http/httptest"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/api"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestWriteS3Error_NoSuchBucket(t *testing.T) {
	rec := httptest.NewRecorder()
	api.WriteS3Error(rec, api.ErrNoSuchBucket, "my-bucket")

	assert.Equal(t, 404, rec.Code)
	assert.Equal(t, "application/xml", rec.Header().Get("Content-Type"))

	var body struct {
		XMLName  xml.Name `xml:"Error"`
		Code     string   `xml:"Code"`
		Message  string   `xml:"Message"`
		Resource string   `xml:"Resource"`
	}
	require.NoError(t, xml.Unmarshal(rec.Body.Bytes(), &body))
	assert.Equal(t, "NoSuchBucket", body.Code)
	assert.Equal(t, "my-bucket", body.Resource)
}

func TestWriteS3Error_StatusCodes(t *testing.T) {
	cases := []struct {
		err  api.S3Error
		want int
	}{
		{api.ErrNoSuchBucket, 404},
		{api.ErrNoSuchKey, 404},
		{api.ErrBucketAlreadyExists, 409},
		{api.ErrBucketNotEmpty, 409},
	}
	for _, c := range cases {
		rec := httptest.NewRecorder()
		api.WriteS3Error(rec, c.err, "res")
		assert.Equal(t, c.want, rec.Code, c.err.Code)
	}
}
