CREATE TABLE objects (
    bucket_name  TEXT NOT NULL REFERENCES buckets(name),
    key          TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes   BIGINT NOT NULL,
    etag         TEXT NOT NULL,
    storage_id   UUID NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bucket_name, key)
);
