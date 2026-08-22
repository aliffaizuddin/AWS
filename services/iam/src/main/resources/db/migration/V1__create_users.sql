CREATE TABLE users (
    id           UUID PRIMARY KEY,
    username     TEXT NOT NULL UNIQUE,
    api_key_hash TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
