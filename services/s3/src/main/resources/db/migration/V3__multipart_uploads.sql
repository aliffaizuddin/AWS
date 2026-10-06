CREATE TABLE multipart_uploads (
    upload_id    UUID PRIMARY KEY,
    bucket_name  TEXT NOT NULL REFERENCES buckets(name),
    key          TEXT NOT NULL,
    content_type TEXT NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'ABORTED')),
    etag         TEXT,
    initiated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX multipart_uploads_bucket_status ON multipart_uploads (bucket_name, status);
CREATE INDEX multipart_uploads_status_updated ON multipart_uploads (status, updated_at);

CREATE TABLE upload_parts (
    upload_id   UUID NOT NULL REFERENCES multipart_uploads(upload_id) ON DELETE CASCADE,
    part_number INT  NOT NULL CHECK (part_number BETWEEN 1 AND 10000),
    storage_id  UUID NOT NULL,
    size_bytes  BIGINT NOT NULL,
    etag        TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (upload_id, part_number)
);

ALTER TABLE objects ALTER COLUMN storage_id DROP NOT NULL;
ALTER TABLE objects ADD COLUMN upload_id UUID REFERENCES multipart_uploads(upload_id);
ALTER TABLE objects ADD CONSTRAINT objects_one_backing
    CHECK ((storage_id IS NULL) <> (upload_id IS NULL));
