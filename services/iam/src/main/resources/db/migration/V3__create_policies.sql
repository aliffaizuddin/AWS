CREATE TABLE policies (
    id         UUID PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    document   JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
