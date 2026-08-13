# S3 Clone Phase 1 (Foundation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up `services/s3` as a working Go service with bucket CRUD and basic object PUT/GET/DELETE/HEAD, wire-compatible with the real AWS S3 REST API, backed by Postgres metadata and UUID-addressed local disk storage.

**Architecture:** A single Go binary (`cmd/s3/main.go`) wiring three internal packages — `storage` (disk blob store), `metadata` (Postgres repos via sqlx), `api` (HTTP handlers + XML marshaling) — behind stdlib `net/http`'s Go 1.22+ `ServeMux` wildcard routing. No auth in this phase.

**Tech Stack:** Go 1.22+, `sqlx` + `golang-migrate` + `pgx` (Postgres driver), `google/uuid`, `stretchr/testify`, `testcontainers-go` (Postgres module) for integration tests.

**Spec:** [`docs/superpowers/specs/2026-08-13-s3-clone-phase1-design.md`](../specs/2026-08-13-s3-clone-phase1-design.md)

## Global Constraints

- Module path: `github.com/aliffaizuddin/AWS/services/s3` (matches this repo's actual GitHub path).
- `go.mod` requires `go 1.22` (floor for `net/http.ServeMux` method+wildcard patterns like `"PUT /{bucket}/{key...}"`).
- Path-style addressing only — no virtual-hosted-style, no wildcard DNS.
- No IAM/auth calls in this phase.
- Every commit follows Conventional Commits (`docs/decisions/0012-commit-and-branch-conventions.md`) — `feat`/`test`/`fix` etc. as appropriate per step.
- All new code lives under `services/s3/`; nothing elsewhere in the repo is modified except `docker-compose.yml` (created, Task 8) and `docs/services/s3.md` (status update, Task 8).

---

### Task 1: Disk-backed blob storage layer

**Files:**
- Create: `services/s3/go.mod`
- Create: `services/s3/internal/storage/store.go`
- Create: `services/s3/internal/storage/disk_store.go`
- Test: `services/s3/internal/storage/disk_store_test.go`

**Interfaces:**
- Produces: `storage.ErrNotFound` (sentinel error); `storage.Store` interface with `Put(ctx, uuid.UUID, io.Reader) error`, `Get(ctx, uuid.UUID) (io.ReadCloser, error)`, `Delete(ctx, uuid.UUID) error`; `storage.NewDiskStore(dataDir string) (*storage.DiskStore, error)` implementing `Store`.

- [ ] **Step 1: Initialize the Go module and dependencies**

```bash
mkdir -p services/s3/internal/storage
cd services/s3
go mod init github.com/aliffaizuddin/AWS/services/s3
go get github.com/google/uuid@latest
go get github.com/stretchr/testify@latest
```

Then edit `services/s3/go.mod` so the `go` directive reads `go 1.22` (the floor this code needs, even if the installed toolchain is newer).

- [ ] **Step 2: Write the failing tests**

Create `services/s3/internal/storage/disk_store_test.go`:

```go
package storage_test

import (
	"bytes"
	"context"
	"io"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/storage"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestDiskStore_PutGetRoundTrip(t *testing.T) {
	dir := t.TempDir()
	store, err := storage.NewDiskStore(dir)
	require.NoError(t, err)

	id := uuid.New()
	ctx := context.Background()
	want := []byte("hello world")

	require.NoError(t, store.Put(ctx, id, bytes.NewReader(want)))

	r, err := store.Get(ctx, id)
	require.NoError(t, err)
	defer r.Close()

	got, err := io.ReadAll(r)
	require.NoError(t, err)
	assert.Equal(t, want, got)
}

func TestDiskStore_Put_Overwrites(t *testing.T) {
	dir := t.TempDir()
	store, err := storage.NewDiskStore(dir)
	require.NoError(t, err)

	id := uuid.New()
	ctx := context.Background()
	require.NoError(t, store.Put(ctx, id, bytes.NewReader([]byte("first"))))
	require.NoError(t, store.Put(ctx, id, bytes.NewReader([]byte("second"))))

	r, err := store.Get(ctx, id)
	require.NoError(t, err)
	defer r.Close()
	got, err := io.ReadAll(r)
	require.NoError(t, err)
	assert.Equal(t, []byte("second"), got)
}

func TestDiskStore_GetMissing_ReturnsErrNotFound(t *testing.T) {
	dir := t.TempDir()
	store, err := storage.NewDiskStore(dir)
	require.NoError(t, err)

	_, err = store.Get(context.Background(), uuid.New())
	assert.ErrorIs(t, err, storage.ErrNotFound)
}

func TestDiskStore_Delete(t *testing.T) {
	dir := t.TempDir()
	store, err := storage.NewDiskStore(dir)
	require.NoError(t, err)

	id := uuid.New()
	ctx := context.Background()
	require.NoError(t, store.Put(ctx, id, bytes.NewReader([]byte("x"))))
	require.NoError(t, store.Delete(ctx, id))

	_, err = store.Get(ctx, id)
	assert.ErrorIs(t, err, storage.ErrNotFound)
}

func TestDiskStore_DeleteMissing_ReturnsErrNotFound(t *testing.T) {
	dir := t.TempDir()
	store, err := storage.NewDiskStore(dir)
	require.NoError(t, err)

	err = store.Delete(context.Background(), uuid.New())
	assert.ErrorIs(t, err, storage.ErrNotFound)
}
```

- [ ] **Step 3: Run the tests and verify they fail**

Run: `cd services/s3 && go test ./internal/storage/... -v`
Expected: build failure — `package storage is not in std` / `undefined: NewDiskStore` (the package doesn't exist yet).

- [ ] **Step 4: Write the implementation**

Create `services/s3/internal/storage/store.go`:

```go
package storage

import (
	"context"
	"errors"
	"io"

	"github.com/google/uuid"
)

// ErrNotFound is returned by Store.Get and Store.Delete when the given id
// has no corresponding blob.
var ErrNotFound = errors.New("storage: object not found")

// Store is a content-agnostic blob store, keyed by a caller-supplied UUID.
// It has no knowledge of buckets or keys — that mapping lives in the
// metadata package.
type Store interface {
	Put(ctx context.Context, id uuid.UUID, r io.Reader) error
	Get(ctx context.Context, id uuid.UUID) (io.ReadCloser, error)
	Delete(ctx context.Context, id uuid.UUID) error
}
```

Create `services/s3/internal/storage/disk_store.go`:

```go
package storage

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/google/uuid"
)

// DiskStore is a Store backed by flat files on local disk, one file per
// blob, named after its UUID.
type DiskStore struct {
	dataDir string
}

// NewDiskStore creates a DiskStore rooted at dataDir, creating the
// directory (and any parents) if it doesn't exist.
func NewDiskStore(dataDir string) (*DiskStore, error) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return nil, fmt.Errorf("storage: create data dir: %w", err)
	}
	return &DiskStore{dataDir: dataDir}, nil
}

func (d *DiskStore) path(id uuid.UUID) string {
	return filepath.Join(d.dataDir, id.String())
}

func (d *DiskStore) Put(ctx context.Context, id uuid.UUID, r io.Reader) error {
	f, err := os.Create(d.path(id))
	if err != nil {
		return fmt.Errorf("storage: create %s: %w", id, err)
	}
	defer f.Close()
	if _, err := io.Copy(f, r); err != nil {
		return fmt.Errorf("storage: write %s: %w", id, err)
	}
	return nil
}

func (d *DiskStore) Get(ctx context.Context, id uuid.UUID) (io.ReadCloser, error) {
	f, err := os.Open(d.path(id))
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("storage: open %s: %w", id, err)
	}
	return f, nil
}

func (d *DiskStore) Delete(ctx context.Context, id uuid.UUID) error {
	err := os.Remove(d.path(id))
	if errors.Is(err, os.ErrNotExist) {
		return ErrNotFound
	}
	if err != nil {
		return fmt.Errorf("storage: remove %s: %w", id, err)
	}
	return nil
}
```

- [ ] **Step 5: Run the tests and verify they pass**

Run: `cd services/s3 && go test ./internal/storage/... -v`
Expected: all 5 tests PASS.

- [ ] **Step 6: Commit**

```bash
cd services/s3
go mod tidy
git add services/s3/go.mod services/s3/go.sum services/s3/internal/storage/
git commit -m "feat: add disk-backed blob storage layer for S3 clone"
```

---

### Task 2: Postgres metadata layer — buckets

**Files:**
- Create: `services/s3/internal/metadata/models.go`
- Create: `services/s3/internal/metadata/errors.go`
- Create: `services/s3/internal/metadata/db.go`
- Create: `services/s3/internal/metadata/bucket_repo.go`
- Create: `services/s3/internal/metadata/migrations/0001_create_buckets.up.sql`
- Create: `services/s3/internal/metadata/migrations/0001_create_buckets.down.sql`
- Test: `services/s3/internal/metadata/bucket_repo_test.go`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `metadata.Bucket{Name string, CreatedAt time.Time}`; `metadata.ErrBucketNotFound`, `metadata.ErrBucketAlreadyExists` (sentinel errors); `metadata.Connect(ctx context.Context, dsn string) (*sqlx.DB, error)` (also runs migrations from the embedded `migrations/` dir); `metadata.BucketRepo` struct wrapping `*sqlx.DB` with methods `Create(ctx, name string) error`, `Get(ctx, name string) (*Bucket, error)`, `Delete(ctx, name string) error`, `List(ctx) ([]Bucket, error)`.

- [ ] **Step 1: Add dependencies**

```bash
cd services/s3
go get github.com/jmoiron/sqlx@latest
go get github.com/jackc/pgx/v5@latest
go get github.com/golang-migrate/migrate/v4@latest
go get github.com/testcontainers/testcontainers-go@latest
go get github.com/testcontainers/testcontainers-go/modules/postgres@latest
```

- [ ] **Step 2: Write the migration files**

Create `services/s3/internal/metadata/migrations/0001_create_buckets.up.sql`:

```sql
CREATE TABLE buckets (
    name       TEXT PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Create `services/s3/internal/metadata/migrations/0001_create_buckets.down.sql`:

```sql
DROP TABLE buckets;
```

- [ ] **Step 3: Write the failing test**

Create `services/s3/internal/metadata/bucket_repo_test.go`:

```go
package metadata_test

import (
	"context"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestBucketRepo_CreateGetDelete(t *testing.T) {
	db := newTestDB(t)
	repo := metadata.NewBucketRepo(db)
	ctx := context.Background()

	require.NoError(t, repo.Create(ctx, "my-bucket"))

	b, err := repo.Get(ctx, "my-bucket")
	require.NoError(t, err)
	assert.Equal(t, "my-bucket", b.Name)

	require.NoError(t, repo.Delete(ctx, "my-bucket"))

	_, err = repo.Get(ctx, "my-bucket")
	assert.ErrorIs(t, err, metadata.ErrBucketNotFound)
}

func TestBucketRepo_Create_Duplicate_ReturnsErrBucketAlreadyExists(t *testing.T) {
	db := newTestDB(t)
	repo := metadata.NewBucketRepo(db)
	ctx := context.Background()

	require.NoError(t, repo.Create(ctx, "dup-bucket"))
	err := repo.Create(ctx, "dup-bucket")
	assert.ErrorIs(t, err, metadata.ErrBucketAlreadyExists)
}

func TestBucketRepo_Delete_Missing_ReturnsErrBucketNotFound(t *testing.T) {
	db := newTestDB(t)
	repo := metadata.NewBucketRepo(db)

	err := repo.Delete(context.Background(), "never-existed")
	assert.ErrorIs(t, err, metadata.ErrBucketNotFound)
}

func TestBucketRepo_List(t *testing.T) {
	db := newTestDB(t)
	repo := metadata.NewBucketRepo(db)
	ctx := context.Background()

	require.NoError(t, repo.Create(ctx, "bucket-a"))
	require.NoError(t, repo.Create(ctx, "bucket-b"))

	buckets, err := repo.List(ctx)
	require.NoError(t, err)
	names := []string{buckets[0].Name, buckets[1].Name}
	assert.ElementsMatch(t, []string{"bucket-a", "bucket-b"}, names)
}
```

Create the shared test helper `services/s3/internal/metadata/testdb_test.go` (used by this and Task 3's tests — starts a real Postgres via testcontainers and applies migrations):

```go
package metadata_test

import (
	"context"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/jmoiron/sqlx"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
)

func newTestDB(t *testing.T) *sqlx.DB {
	t.Helper()
	ctx := context.Background()

	container, err := tcpostgres.Run(ctx, "postgres:16-alpine",
		tcpostgres.WithDatabase("s3test"),
		tcpostgres.WithUsername("s3test"),
		tcpostgres.WithPassword("s3test"),
	)
	if err != nil {
		t.Fatalf("start postgres container: %v", err)
	}
	t.Cleanup(func() { _ = container.Terminate(ctx) })

	dsn, err := container.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		t.Fatalf("get connection string: %v", err)
	}

	db, err := metadata.Connect(ctx, dsn)
	if err != nil {
		t.Fatalf("connect + migrate: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	return db
}
```

- [ ] **Step 4: Run the test and verify it fails**

Run: `cd services/s3 && go test ./internal/metadata/... -v`
Expected: build failure — `metadata.Connect`, `metadata.NewBucketRepo` etc. undefined.

- [ ] **Step 5: Write the implementation**

Create `services/s3/internal/metadata/models.go`:

```go
package metadata

import "time"

// Bucket is a single row in the buckets table.
type Bucket struct {
	Name      string    `db:"name"`
	CreatedAt time.Time `db:"created_at"`
}
```

Create `services/s3/internal/metadata/errors.go`:

```go
package metadata

import "errors"

var (
	ErrBucketNotFound      = errors.New("metadata: bucket not found")
	ErrBucketAlreadyExists = errors.New("metadata: bucket already exists")
	ErrObjectNotFound      = errors.New("metadata: object not found")
)
```

(`ErrObjectNotFound` is declared here now, unused until Task 3, so both error files land in one place.)

Create `services/s3/internal/metadata/db.go`:

```go
package metadata

import (
	"context"
	"embed"
	"fmt"

	"github.com/golang-migrate/migrate/v4"
	"github.com/golang-migrate/migrate/v4/database/postgres"
	"github.com/golang-migrate/migrate/v4/source/iofs"
	"github.com/jmoiron/sqlx"

	_ "github.com/jackc/pgx/v5/stdlib" // registers the "pgx" sql driver
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

// Connect opens a Postgres connection pool at dsn and runs all pending
// migrations before returning.
func Connect(ctx context.Context, dsn string) (*sqlx.DB, error) {
	db, err := sqlx.ConnectContext(ctx, "pgx", dsn)
	if err != nil {
		return nil, fmt.Errorf("metadata: connect: %w", err)
	}

	if err := migrateUp(db, dsn); err != nil {
		db.Close()
		return nil, err
	}

	return db, nil
}

func migrateUp(db *sqlx.DB, dsn string) error {
	src, err := iofs.New(migrationsFS, "migrations")
	if err != nil {
		return fmt.Errorf("metadata: load migrations: %w", err)
	}

	driver, err := postgres.WithInstance(db.DB, &postgres.Config{})
	if err != nil {
		return fmt.Errorf("metadata: migration driver: %w", err)
	}

	m, err := migrate.NewWithInstance("iofs", src, "postgres", driver)
	if err != nil {
		return fmt.Errorf("metadata: init migrator: %w", err)
	}

	if err := m.Up(); err != nil && err != migrate.ErrNoChange {
		return fmt.Errorf("metadata: run migrations: %w", err)
	}
	return nil
}
```

Create `services/s3/internal/metadata/bucket_repo.go`:

```go
package metadata

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jmoiron/sqlx"
)

// BucketRepo provides CRUD access to the buckets table.
type BucketRepo struct {
	db *sqlx.DB
}

// NewBucketRepo constructs a BucketRepo over an already-connected,
// already-migrated db (see Connect).
func NewBucketRepo(db *sqlx.DB) *BucketRepo {
	return &BucketRepo{db: db}
}

func (r *BucketRepo) Create(ctx context.Context, name string) error {
	_, err := r.db.ExecContext(ctx, `INSERT INTO buckets (name) VALUES ($1)`, name)
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" { // unique_violation
			return ErrBucketAlreadyExists
		}
		return fmt.Errorf("metadata: create bucket %s: %w", name, err)
	}
	return nil
}

func (r *BucketRepo) Get(ctx context.Context, name string) (*Bucket, error) {
	var b Bucket
	err := r.db.GetContext(ctx, &b, `SELECT name, created_at FROM buckets WHERE name = $1`, name)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrBucketNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("metadata: get bucket %s: %w", name, err)
	}
	return &b, nil
}

func (r *BucketRepo) Delete(ctx context.Context, name string) error {
	res, err := r.db.ExecContext(ctx, `DELETE FROM buckets WHERE name = $1`, name)
	if err != nil {
		return fmt.Errorf("metadata: delete bucket %s: %w", name, err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("metadata: delete bucket %s: %w", name, err)
	}
	if n == 0 {
		return ErrBucketNotFound
	}
	return nil
}

func (r *BucketRepo) List(ctx context.Context) ([]Bucket, error) {
	var buckets []Bucket
	err := r.db.SelectContext(ctx, &buckets, `SELECT name, created_at FROM buckets ORDER BY name`)
	if err != nil {
		return nil, fmt.Errorf("metadata: list buckets: %w", err)
	}
	return buckets, nil
}
```

- [ ] **Step 6: Run the tests and verify they pass**

Run: `cd services/s3 && go test ./internal/metadata/... -v`
Expected: all 4 tests PASS. (Requires Docker running locally — testcontainers starts a real `postgres:16-alpine` container per test.)

- [ ] **Step 7: Commit**

```bash
cd services/s3
go mod tidy
git add services/s3/go.mod services/s3/go.sum services/s3/internal/metadata/
git commit -m "feat: add Postgres bucket metadata repo with migrations"
```

---

### Task 3: Postgres metadata layer — objects

**Files:**
- Create: `services/s3/internal/metadata/migrations/0002_create_objects.up.sql`
- Create: `services/s3/internal/metadata/migrations/0002_create_objects.down.sql`
- Create: `services/s3/internal/metadata/object_repo.go`
- Modify: `services/s3/internal/metadata/models.go` (add `Object` struct)
- Test: `services/s3/internal/metadata/object_repo_test.go`

**Interfaces:**
- Consumes: `metadata.Connect`, `metadata.NewBucketRepo`, `metadata.ErrBucketNotFound` (Task 2); the `newTestDB(t)` helper (Task 2's `testdb_test.go`).
- Produces: `metadata.Object{BucketName string, Key string, ContentType string, SizeBytes int64, ETag string, StorageID uuid.UUID, CreatedAt time.Time}`; `metadata.NewObjectRepo(db *sqlx.DB) *ObjectRepo` with methods `Put(ctx, obj Object) error`, `Get(ctx, bucket, key string) (*Object, error)`, `Delete(ctx, bucket, key string) error`, `ListByBucket(ctx, bucket string) ([]Object, error)`.

- [ ] **Step 1: Write the migration files**

Create `services/s3/internal/metadata/migrations/0002_create_objects.up.sql`:

```sql
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
```

Create `services/s3/internal/metadata/migrations/0002_create_objects.down.sql`:

```sql
DROP TABLE objects;
```

- [ ] **Step 2: Write the failing test**

Create `services/s3/internal/metadata/object_repo_test.go`:

```go
package metadata_test

import (
	"context"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestObjectRepo_PutGetDelete(t *testing.T) {
	db := newTestDB(t)
	buckets := metadata.NewBucketRepo(db)
	objects := metadata.NewObjectRepo(db)
	ctx := context.Background()

	require.NoError(t, buckets.Create(ctx, "obj-bucket"))

	storageID := uuid.New()
	obj := metadata.Object{
		BucketName:  "obj-bucket",
		Key:         "hello.txt",
		ContentType: "text/plain",
		SizeBytes:   11,
		ETag:        "abc123",
		StorageID:   storageID,
	}
	require.NoError(t, objects.Put(ctx, obj))

	got, err := objects.Get(ctx, "obj-bucket", "hello.txt")
	require.NoError(t, err)
	assert.Equal(t, "text/plain", got.ContentType)
	assert.Equal(t, storageID, got.StorageID)

	require.NoError(t, objects.Delete(ctx, "obj-bucket", "hello.txt"))
	_, err = objects.Get(ctx, "obj-bucket", "hello.txt")
	assert.ErrorIs(t, err, metadata.ErrObjectNotFound)
}

func TestObjectRepo_Put_OverwritesExistingKey(t *testing.T) {
	db := newTestDB(t)
	buckets := metadata.NewBucketRepo(db)
	objects := metadata.NewObjectRepo(db)
	ctx := context.Background()
	require.NoError(t, buckets.Create(ctx, "overwrite-bucket"))

	first := metadata.Object{BucketName: "overwrite-bucket", Key: "k", ContentType: "text/plain", SizeBytes: 1, ETag: "e1", StorageID: uuid.New()}
	second := metadata.Object{BucketName: "overwrite-bucket", Key: "k", ContentType: "application/json", SizeBytes: 2, ETag: "e2", StorageID: uuid.New()}

	require.NoError(t, objects.Put(ctx, first))
	require.NoError(t, objects.Put(ctx, second))

	got, err := objects.Get(ctx, "overwrite-bucket", "k")
	require.NoError(t, err)
	assert.Equal(t, "application/json", got.ContentType)
	assert.Equal(t, "e2", got.ETag)
}

func TestObjectRepo_Get_Missing_ReturnsErrObjectNotFound(t *testing.T) {
	db := newTestDB(t)
	buckets := metadata.NewBucketRepo(db)
	objects := metadata.NewObjectRepo(db)
	ctx := context.Background()
	require.NoError(t, buckets.Create(ctx, "empty-bucket"))

	_, err := objects.Get(ctx, "empty-bucket", "missing")
	assert.ErrorIs(t, err, metadata.ErrObjectNotFound)
}

func TestObjectRepo_Delete_Missing_ReturnsErrObjectNotFound(t *testing.T) {
	db := newTestDB(t)
	buckets := metadata.NewBucketRepo(db)
	objects := metadata.NewObjectRepo(db)
	ctx := context.Background()
	require.NoError(t, buckets.Create(ctx, "empty-bucket-2"))

	err := objects.Delete(ctx, "empty-bucket-2", "missing")
	assert.ErrorIs(t, err, metadata.ErrObjectNotFound)
}

func TestObjectRepo_ListByBucket(t *testing.T) {
	db := newTestDB(t)
	buckets := metadata.NewBucketRepo(db)
	objects := metadata.NewObjectRepo(db)
	ctx := context.Background()
	require.NoError(t, buckets.Create(ctx, "list-bucket"))

	require.NoError(t, objects.Put(ctx, metadata.Object{BucketName: "list-bucket", Key: "a", ContentType: "text/plain", SizeBytes: 1, ETag: "e", StorageID: uuid.New()}))
	require.NoError(t, objects.Put(ctx, metadata.Object{BucketName: "list-bucket", Key: "b", ContentType: "text/plain", SizeBytes: 1, ETag: "e", StorageID: uuid.New()}))

	list, err := objects.ListByBucket(ctx, "list-bucket")
	require.NoError(t, err)
	assert.Len(t, list, 2)
}
```

- [ ] **Step 3: Run the test and verify it fails**

Run: `cd services/s3 && go test ./internal/metadata/... -run TestObjectRepo -v`
Expected: build failure — `metadata.NewObjectRepo`, `metadata.Object` undefined.

- [ ] **Step 4: Write the implementation**

Modify `services/s3/internal/metadata/models.go`, adding below the `Bucket` struct:

```go

// Object is a single row in the objects table — Phase 1 keeps one row per
// (bucket_name, key), i.e. no version history yet.
type Object struct {
	BucketName  string    `db:"bucket_name"`
	Key         string    `db:"key"`
	ContentType string    `db:"content_type"`
	SizeBytes   int64     `db:"size_bytes"`
	ETag        string    `db:"etag"`
	StorageID   uuid.UUID `db:"storage_id"`
	CreatedAt   time.Time `db:"created_at"`
}
```

Add the import: `"github.com/google/uuid"` to `models.go`'s import block.

Create `services/s3/internal/metadata/object_repo.go`:

```go
package metadata

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/jmoiron/sqlx"
)

// ObjectRepo provides CRUD access to the objects table.
type ObjectRepo struct {
	db *sqlx.DB
}

func NewObjectRepo(db *sqlx.DB) *ObjectRepo {
	return &ObjectRepo{db: db}
}

// Put inserts obj, or overwrites the existing row for the same
// (bucket_name, key) — Phase 1 has no versioning, so a second PUT to the
// same key replaces it.
func (r *ObjectRepo) Put(ctx context.Context, obj Object) error {
	_, err := r.db.ExecContext(ctx, `
		INSERT INTO objects (bucket_name, key, content_type, size_bytes, etag, storage_id)
		VALUES ($1, $2, $3, $4, $5, $6)
		ON CONFLICT (bucket_name, key) DO UPDATE SET
			content_type = EXCLUDED.content_type,
			size_bytes   = EXCLUDED.size_bytes,
			etag         = EXCLUDED.etag,
			storage_id   = EXCLUDED.storage_id,
			created_at   = now()
	`, obj.BucketName, obj.Key, obj.ContentType, obj.SizeBytes, obj.ETag, obj.StorageID)
	if err != nil {
		return fmt.Errorf("metadata: put object %s/%s: %w", obj.BucketName, obj.Key, err)
	}
	return nil
}

func (r *ObjectRepo) Get(ctx context.Context, bucket, key string) (*Object, error) {
	var o Object
	err := r.db.GetContext(ctx, &o, `
		SELECT bucket_name, key, content_type, size_bytes, etag, storage_id, created_at
		FROM objects WHERE bucket_name = $1 AND key = $2
	`, bucket, key)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrObjectNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("metadata: get object %s/%s: %w", bucket, key, err)
	}
	return &o, nil
}

func (r *ObjectRepo) Delete(ctx context.Context, bucket, key string) error {
	res, err := r.db.ExecContext(ctx, `DELETE FROM objects WHERE bucket_name = $1 AND key = $2`, bucket, key)
	if err != nil {
		return fmt.Errorf("metadata: delete object %s/%s: %w", bucket, key, err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("metadata: delete object %s/%s: %w", bucket, key, err)
	}
	if n == 0 {
		return ErrObjectNotFound
	}
	return nil
}

func (r *ObjectRepo) ListByBucket(ctx context.Context, bucket string) ([]Object, error) {
	var objs []Object
	err := r.db.SelectContext(ctx, &objs, `
		SELECT bucket_name, key, content_type, size_bytes, etag, storage_id, created_at
		FROM objects WHERE bucket_name = $1 ORDER BY key
	`, bucket)
	if err != nil {
		return nil, fmt.Errorf("metadata: list objects in %s: %w", bucket, err)
	}
	return objs, nil
}
```

- [ ] **Step 5: Run the tests and verify they pass**

Run: `cd services/s3 && go test ./internal/metadata/... -v`
Expected: all tests in the package PASS (Task 2's bucket tests + this task's object tests).

- [ ] **Step 6: Commit**

```bash
git add services/s3/internal/metadata/
git commit -m "feat: add Postgres object metadata repo"
```

---

### Task 4: S3-shaped XML responses and errors

**Files:**
- Create: `services/s3/internal/api/xml_responses.go`
- Create: `services/s3/internal/api/errors.go`
- Test: `services/s3/internal/api/xml_responses_test.go`
- Test: `services/s3/internal/api/errors_test.go`

**Interfaces:**
- Consumes: `metadata.Bucket` (Task 2) for building `ListAllMyBucketsResult`.
- Produces: `api.ListAllMyBucketsResult`, `api.Owner`, `api.BucketsXML`, `api.BucketXML` (XML-taggable structs); `api.WriteXML(w http.ResponseWriter, statusCode int, v any)` helper; `api.S3Error{Code, Message string}` plus vars `api.ErrNoSuchBucket`, `api.ErrNoSuchKey`, `api.ErrBucketAlreadyExists`, `api.ErrBucketNotEmpty` (each with an associated HTTP status via `api.StatusFor(S3Error) int`); `api.WriteS3Error(w http.ResponseWriter, e S3Error, resource string)`.

- [ ] **Step 1: Add the XML request-id dependency**

```bash
cd services/s3
go get github.com/google/uuid@latest # already present from Task 1, no-op if so
```

- [ ] **Step 2: Write the failing tests**

Create `services/s3/internal/api/xml_responses_test.go`:

```go
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
```

Create `services/s3/internal/api/errors_test.go`:

```go
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
		XMLName xml.Name `xml:"Error"`
		Code    string   `xml:"Code"`
		Message string   `xml:"Message"`
		Resource string  `xml:"Resource"`
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
```

- [ ] **Step 3: Run the tests and verify they fail**

Run: `cd services/s3 && go test ./internal/api/... -v`
Expected: build failure — `api` package doesn't exist yet.

- [ ] **Step 4: Write the implementation**

Create `services/s3/internal/api/xml_responses.go`:

```go
package api

import (
	"encoding/xml"
	"net/http"
	"time"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
)

// WriteXML marshals v as XML, writes the XML content-type header, and
// writes statusCode + the marshaled body to w.
func WriteXML(w http.ResponseWriter, statusCode int, v any) {
	body, err := xml.MarshalIndent(v, "", "  ")
	if err != nil {
		// Marshaling one of our own response structs should never fail;
		// if it does, there's nothing more specific to tell the caller.
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/xml")
	w.WriteHeader(statusCode)
	_, _ = w.Write([]byte(xml.Header))
	_, _ = w.Write(body)
}

// ListAllMyBucketsResult is the XML body for the ListBuckets operation.
type ListAllMyBucketsResult struct {
	XMLName xml.Name    `xml:"ListAllMyBucketsResult"`
	Owner   Owner       `xml:"Owner"`
	Buckets BucketsXML  `xml:"Buckets"`
}

type Owner struct {
	ID          string `xml:"ID"`
	DisplayName string `xml:"DisplayName"`
}

type BucketsXML struct {
	Bucket []BucketXML `xml:"Bucket"`
}

type BucketXML struct {
	Name         string    `xml:"Name"`
	CreationDate time.Time `xml:"CreationDate"`
}

// NewListAllMyBucketsResult builds the XML response body from metadata
// rows. Owner is a fixed placeholder — Phase 1 has no IAM/identity wiring.
func NewListAllMyBucketsResult(buckets []metadata.Bucket) ListAllMyBucketsResult {
	xmlBuckets := make([]BucketXML, len(buckets))
	for i, b := range buckets {
		xmlBuckets[i] = BucketXML{Name: b.Name, CreationDate: b.CreatedAt}
	}
	return ListAllMyBucketsResult{
		Owner:   Owner{ID: "cloudlite", DisplayName: "cloudlite"},
		Buckets: BucketsXML{Bucket: xmlBuckets},
	}
}
```

Create `services/s3/internal/api/errors.go`:

```go
package api

import (
	"encoding/xml"
	"net/http"

	"github.com/google/uuid"
)

// S3Error is an AWS-shaped error: a stable Code (what SDKs branch on) and
// a human-readable Message.
type S3Error struct {
	Code    string
	Message string
}

var (
	ErrNoSuchBucket        = S3Error{Code: "NoSuchBucket", Message: "The specified bucket does not exist"}
	ErrNoSuchKey           = S3Error{Code: "NoSuchKey", Message: "The specified key does not exist"}
	ErrBucketAlreadyExists = S3Error{Code: "BucketAlreadyExists", Message: "The requested bucket name is not available"}
	ErrBucketNotEmpty      = S3Error{Code: "BucketNotEmpty", Message: "The bucket you tried to delete is not empty"}
)

// StatusFor maps an S3Error to the HTTP status code it's written with.
func StatusFor(e S3Error) int {
	switch e.Code {
	case ErrNoSuchBucket.Code, ErrNoSuchKey.Code:
		return http.StatusNotFound
	case ErrBucketAlreadyExists.Code, ErrBucketNotEmpty.Code:
		return http.StatusConflict
	default:
		return http.StatusInternalServerError
	}
}

type errorXML struct {
	XMLName   xml.Name `xml:"Error"`
	Code      string   `xml:"Code"`
	Message   string   `xml:"Message"`
	Resource  string   `xml:"Resource"`
	RequestID string   `xml:"RequestId"`
}

// WriteS3Error writes e as an AWS-shaped XML error body, with resource
// identifying the bucket or key the error is about.
func WriteS3Error(w http.ResponseWriter, e S3Error, resource string) {
	WriteXML(w, StatusFor(e), errorXML{
		Code:      e.Code,
		Message:   e.Message,
		Resource:  resource,
		RequestID: uuid.NewString(),
	})
}
```

- [ ] **Step 5: Run the tests and verify they pass**

Run: `cd services/s3 && go test ./internal/api/... -v`
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add services/s3/internal/api/ services/s3/go.mod services/s3/go.sum
git commit -m "feat: add S3-shaped XML response and error helpers"
```

---

### Task 5: Bucket HTTP handlers

**Files:**
- Create: `services/s3/internal/api/bucket_handlers.go`
- Test: `services/s3/internal/api/bucket_handlers_test.go`

**Interfaces:**
- Consumes: `api.WriteXML`, `api.WriteS3Error`, `api.ErrNoSuchBucket`, `api.ErrBucketAlreadyExists`, `api.ErrBucketNotEmpty`, `api.NewListAllMyBucketsResult` (Task 4); `metadata.Bucket`, `metadata.ErrBucketNotFound`, `metadata.ErrBucketAlreadyExists` (Task 2); `metadata.Object`, `metadata.ErrObjectNotFound` (Task 3, for the emptiness check on delete) — but this task defines its own narrow repo interfaces (below) rather than depending on the concrete `*metadata.BucketRepo`/`*metadata.ObjectRepo` types, so it can be unit-tested with fakes.
- Produces: `api.BucketRepo` interface (`Create`, `Get`, `Delete`, `List` — same signatures as `*metadata.BucketRepo`); `api.ObjectLister` interface (`ListByBucket(ctx, bucket string) ([]metadata.Object, error)` — same signature as `*metadata.ObjectRepo`); `api.BucketHandlers{Buckets BucketRepo, Objects ObjectLister}` with methods `Create`, `List`, `Head`, `Delete` (each `func(w http.ResponseWriter, r *http.Request)`).

- [ ] **Step 1: Write the failing tests**

Create `services/s3/internal/api/bucket_handlers_test.go`:

```go
package api_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/api"
	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// fakeBucketRepo and fakeObjectLister are in-memory test doubles — see
// fakes_test.go for their shared definitions used across handler tests.

func TestBucketHandlers_Create(t *testing.T) {
	buckets := newFakeBucketRepo()
	h := api.BucketHandlers{Buckets: buckets, Objects: newFakeObjectLister()}

	req := httptest.NewRequest(http.MethodPut, "/my-bucket", nil)
	req.SetPathValue("bucket", "my-bucket")
	rec := httptest.NewRecorder()

	h.Create(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	_, err := buckets.Get(context.Background(), "my-bucket")
	require.NoError(t, err)
}

func TestBucketHandlers_Create_Duplicate(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "dup"))
	h := api.BucketHandlers{Buckets: buckets, Objects: newFakeObjectLister()}

	req := httptest.NewRequest(http.MethodPut, "/dup", nil)
	req.SetPathValue("bucket", "dup")
	rec := httptest.NewRecorder()

	h.Create(rec, req)

	assert.Equal(t, http.StatusConflict, rec.Code)
}

func TestBucketHandlers_List(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "a"))
	require.NoError(t, buckets.Create(context.Background(), "b"))
	h := api.BucketHandlers{Buckets: buckets, Objects: newFakeObjectLister()}

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	rec := httptest.NewRecorder()

	h.List(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), "<Name>a</Name>")
	assert.Contains(t, rec.Body.String(), "<Name>b</Name>")
}

func TestBucketHandlers_Head_Exists(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "exists"))
	h := api.BucketHandlers{Buckets: buckets, Objects: newFakeObjectLister()}

	req := httptest.NewRequest(http.MethodHead, "/exists", nil)
	req.SetPathValue("bucket", "exists")
	rec := httptest.NewRecorder()

	h.Head(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestBucketHandlers_Head_Missing(t *testing.T) {
	h := api.BucketHandlers{Buckets: newFakeBucketRepo(), Objects: newFakeObjectLister()}

	req := httptest.NewRequest(http.MethodHead, "/missing", nil)
	req.SetPathValue("bucket", "missing")
	rec := httptest.NewRecorder()

	h.Head(rec, req)

	assert.Equal(t, http.StatusNotFound, rec.Code)
}

func TestBucketHandlers_Delete_Empty(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "empty"))
	h := api.BucketHandlers{Buckets: buckets, Objects: newFakeObjectLister()}

	req := httptest.NewRequest(http.MethodDelete, "/empty", nil)
	req.SetPathValue("bucket", "empty")
	rec := httptest.NewRecorder()

	h.Delete(rec, req)

	assert.Equal(t, http.StatusNoContent, rec.Code)
}

func TestBucketHandlers_Delete_NotEmpty(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "full"))
	objects := newFakeObjectLister()
	objects.add("full", metadata.Object{BucketName: "full", Key: "k"})
	h := api.BucketHandlers{Buckets: buckets, Objects: objects}

	req := httptest.NewRequest(http.MethodDelete, "/full", nil)
	req.SetPathValue("bucket", "full")
	rec := httptest.NewRecorder()

	h.Delete(rec, req)

	assert.Equal(t, http.StatusConflict, rec.Code)
}

func TestBucketHandlers_Delete_Missing(t *testing.T) {
	h := api.BucketHandlers{Buckets: newFakeBucketRepo(), Objects: newFakeObjectLister()}

	req := httptest.NewRequest(http.MethodDelete, "/missing", nil)
	req.SetPathValue("bucket", "missing")
	rec := httptest.NewRecorder()

	h.Delete(rec, req)

	assert.Equal(t, http.StatusNotFound, rec.Code)
}
```

Create `services/s3/internal/api/fakes_test.go` (shared in-memory test doubles for `BucketRepo` and `ObjectLister`, used by this task and Task 6):

```go
package api_test

import (
	"context"
	"sort"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
)

type fakeBucketRepo struct {
	buckets map[string]metadata.Bucket
}

func newFakeBucketRepo() *fakeBucketRepo {
	return &fakeBucketRepo{buckets: map[string]metadata.Bucket{}}
}

func (f *fakeBucketRepo) Create(ctx context.Context, name string) error {
	if _, ok := f.buckets[name]; ok {
		return metadata.ErrBucketAlreadyExists
	}
	f.buckets[name] = metadata.Bucket{Name: name}
	return nil
}

func (f *fakeBucketRepo) Get(ctx context.Context, name string) (*metadata.Bucket, error) {
	b, ok := f.buckets[name]
	if !ok {
		return nil, metadata.ErrBucketNotFound
	}
	return &b, nil
}

func (f *fakeBucketRepo) Delete(ctx context.Context, name string) error {
	if _, ok := f.buckets[name]; !ok {
		return metadata.ErrBucketNotFound
	}
	delete(f.buckets, name)
	return nil
}

func (f *fakeBucketRepo) List(ctx context.Context) ([]metadata.Bucket, error) {
	names := make([]string, 0, len(f.buckets))
	for name := range f.buckets {
		names = append(names, name)
	}
	sort.Strings(names)
	out := make([]metadata.Bucket, len(names))
	for i, name := range names {
		out[i] = f.buckets[name]
	}
	return out, nil
}

type fakeObjectLister struct {
	objects map[string][]metadata.Object
}

func newFakeObjectLister() *fakeObjectLister {
	return &fakeObjectLister{objects: map[string][]metadata.Object{}}
}

func (f *fakeObjectLister) add(bucket string, obj metadata.Object) {
	f.objects[bucket] = append(f.objects[bucket], obj)
}

func (f *fakeObjectLister) ListByBucket(ctx context.Context, bucket string) ([]metadata.Object, error) {
	return f.objects[bucket], nil
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `cd services/s3 && go test ./internal/api/... -run TestBucketHandlers -v`
Expected: build failure — `api.BucketHandlers` undefined.

- [ ] **Step 3: Write the implementation**

Create `services/s3/internal/api/bucket_handlers.go`:

```go
package api

import (
	"context"
	"errors"
	"net/http"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
)

// BucketRepo is the subset of metadata.BucketRepo's methods the bucket
// handlers need — satisfied by *metadata.BucketRepo in production and by
// an in-memory fake in tests.
type BucketRepo interface {
	Create(ctx context.Context, name string) error
	Get(ctx context.Context, name string) (*metadata.Bucket, error)
	Delete(ctx context.Context, name string) error
	List(ctx context.Context) ([]metadata.Bucket, error)
}

// ObjectLister is the subset of metadata.ObjectRepo's methods the bucket
// handlers need (only to check emptiness before a bucket delete).
type ObjectLister interface {
	ListByBucket(ctx context.Context, bucket string) ([]metadata.Object, error)
}

type BucketHandlers struct {
	Buckets BucketRepo
	Objects ObjectLister
}

func (h *BucketHandlers) Create(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("bucket")
	err := h.Buckets.Create(r.Context(), name)
	if errors.Is(err, metadata.ErrBucketAlreadyExists) {
		WriteS3Error(w, ErrBucketAlreadyExists, name)
		return
	}
	if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, name)
		return
	}
	w.WriteHeader(http.StatusOK)
}

func (h *BucketHandlers) List(w http.ResponseWriter, r *http.Request) {
	buckets, err := h.Buckets.List(r.Context())
	if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, "")
		return
	}
	WriteXML(w, http.StatusOK, NewListAllMyBucketsResult(buckets))
}

func (h *BucketHandlers) Head(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("bucket")
	_, err := h.Buckets.Get(r.Context(), name)
	if errors.Is(err, metadata.ErrBucketNotFound) {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusOK)
}

func (h *BucketHandlers) Delete(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("bucket")

	objs, err := h.Objects.ListByBucket(r.Context(), name)
	if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, name)
		return
	}
	if len(objs) > 0 {
		WriteS3Error(w, ErrBucketNotEmpty, name)
		return
	}

	err = h.Buckets.Delete(r.Context(), name)
	if errors.Is(err, metadata.ErrBucketNotFound) {
		WriteS3Error(w, ErrNoSuchBucket, name)
		return
	}
	if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, name)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
```

- [ ] **Step 4: Run the tests and verify they pass**

Run: `cd services/s3 && go test ./internal/api/... -run TestBucketHandlers -v`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add services/s3/internal/api/
git commit -m "feat: add bucket HTTP handlers"
```

---

### Task 6: Object HTTP handlers

**Files:**
- Create: `services/s3/internal/api/object_handlers.go`
- Test: `services/s3/internal/api/object_handlers_test.go`
- Modify: `services/s3/internal/api/fakes_test.go` (add a fake `ObjectRepo` + fake `storage.Store`)

**Interfaces:**
- Consumes: `api.WriteS3Error`, `ErrNoSuchBucket`/`ErrNoSuchKey` (Task 4); `metadata.Object`, `metadata.ErrObjectNotFound` (Task 3); `storage.Store`, `storage.ErrNotFound` (Task 1); `api.BucketRepo` (Task 5, to verify the bucket exists before a PUT).
- Produces: `api.ObjectRepo` interface (`Put(ctx, obj metadata.Object) error`, `Get(ctx, bucket, key string) (*metadata.Object, error)`, `Delete(ctx, bucket, key string) error` — same signatures as `*metadata.ObjectRepo`); `api.ObjectHandlers{Buckets BucketRepo, Objects ObjectRepo, Store storage.Store}` with methods `Put`, `Get`, `Head`, `Delete`.

- [ ] **Step 1: Extend the fakes**

Add to `services/s3/internal/api/fakes_test.go`:

```go

type fakeObjectRepo struct {
	objects map[string]metadata.Object // key: bucket+"/"+key
}

func newFakeObjectRepo() *fakeObjectRepo {
	return &fakeObjectRepo{objects: map[string]metadata.Object{}}
}

func (f *fakeObjectRepo) key(bucket, key string) string { return bucket + "/" + key }

func (f *fakeObjectRepo) Put(ctx context.Context, obj metadata.Object) error {
	f.objects[f.key(obj.BucketName, obj.Key)] = obj
	return nil
}

func (f *fakeObjectRepo) Get(ctx context.Context, bucket, key string) (*metadata.Object, error) {
	o, ok := f.objects[f.key(bucket, key)]
	if !ok {
		return nil, metadata.ErrObjectNotFound
	}
	return &o, nil
}

func (f *fakeObjectRepo) Delete(ctx context.Context, bucket, key string) error {
	delete(f.objects, f.key(bucket, key))
	return nil
}

type fakeStore struct {
	blobs map[string][]byte
}

func newFakeStore() *fakeStore {
	return &fakeStore{blobs: map[string][]byte{}}
}

func (f *fakeStore) Put(ctx context.Context, id uuid.UUID, r io.Reader) error {
	b, err := io.ReadAll(r)
	if err != nil {
		return err
	}
	f.blobs[id.String()] = b
	return nil
}

func (f *fakeStore) Get(ctx context.Context, id uuid.UUID) (io.ReadCloser, error) {
	b, ok := f.blobs[id.String()]
	if !ok {
		return nil, storage.ErrNotFound
	}
	return io.NopCloser(bytes.NewReader(b)), nil
}

func (f *fakeStore) Delete(ctx context.Context, id uuid.UUID) error {
	delete(f.blobs, id.String())
	return nil
}
```

Add these imports to `fakes_test.go`'s import block: `"bytes"`, `"io"`, `"github.com/aliffaizuddin/AWS/services/s3/internal/storage"`, `"github.com/google/uuid"`.

- [ ] **Step 2: Write the failing tests**

Create `services/s3/internal/api/object_handlers_test.go`:

```go
package api_test

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/api"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestObjectHandlers_Put_Get_RoundTrip(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "b"))
	h := api.ObjectHandlers{Buckets: buckets, Objects: newFakeObjectRepo(), Store: newFakeStore()}

	putReq := httptest.NewRequest(http.MethodPut, "/b/k.txt", bytes.NewReader([]byte("hello")))
	putReq.SetPathValue("bucket", "b")
	putReq.SetPathValue("key", "k.txt")
	putReq.Header.Set("Content-Type", "text/plain")
	putRec := httptest.NewRecorder()
	h.Put(putRec, putReq)
	require.Equal(t, http.StatusOK, putRec.Code)
	require.NotEmpty(t, putRec.Header().Get("ETag"))

	getReq := httptest.NewRequest(http.MethodGet, "/b/k.txt", nil)
	getReq.SetPathValue("bucket", "b")
	getReq.SetPathValue("key", "k.txt")
	getRec := httptest.NewRecorder()
	h.Get(getRec, getReq)

	assert.Equal(t, http.StatusOK, getRec.Code)
	assert.Equal(t, "text/plain", getRec.Header().Get("Content-Type"))
	assert.Equal(t, "hello", getRec.Body.String())
}

func TestObjectHandlers_Put_MissingBucket(t *testing.T) {
	h := api.ObjectHandlers{Buckets: newFakeBucketRepo(), Objects: newFakeObjectRepo(), Store: newFakeStore()}

	req := httptest.NewRequest(http.MethodPut, "/missing/k.txt", bytes.NewReader([]byte("x")))
	req.SetPathValue("bucket", "missing")
	req.SetPathValue("key", "k.txt")
	rec := httptest.NewRecorder()
	h.Put(rec, req)

	assert.Equal(t, http.StatusNotFound, rec.Code)
}

func TestObjectHandlers_Get_MissingKey(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "b"))
	h := api.ObjectHandlers{Buckets: buckets, Objects: newFakeObjectRepo(), Store: newFakeStore()}

	req := httptest.NewRequest(http.MethodGet, "/b/missing.txt", nil)
	req.SetPathValue("bucket", "b")
	req.SetPathValue("key", "missing.txt")
	rec := httptest.NewRecorder()
	h.Get(rec, req)

	assert.Equal(t, http.StatusNotFound, rec.Code)
}

func TestObjectHandlers_Head(t *testing.T) {
	buckets := newFakeBucketRepo()
	require.NoError(t, buckets.Create(context.Background(), "b"))
	h := api.ObjectHandlers{Buckets: buckets, Objects: newFakeObjectRepo(), Store: newFakeStore()}

	putReq := httptest.NewRequest(http.MethodPut, "/b/k.txt", bytes.NewReader([]byte("hello")))
	putReq.SetPathValue("bucket", "b")
	putReq.SetPathValue("key", "k.txt")
	h.Put(httptest.NewRecorder(), putReq)

	headReq := httptest.NewRequest(http.MethodHead, "/b/k.txt", nil)
	headReq.SetPathValue("bucket", "b")
	headReq.SetPathValue("key", "k.txt")
	headRec := httptest.NewRecorder()
	h.Head(headRec, headReq)

	assert.Equal(t, http.StatusOK, headRec.Code)
	assert.Empty(t, headRec.Body.String())
}

func TestObjectHandlers_Delete_AlwaysNoContent(t *testing.T) {
	h := api.ObjectHandlers{Buckets: newFakeBucketRepo(), Objects: newFakeObjectRepo(), Store: newFakeStore()}

	req := httptest.NewRequest(http.MethodDelete, "/b/never-existed.txt", nil)
	req.SetPathValue("bucket", "b")
	req.SetPathValue("key", "never-existed.txt")
	rec := httptest.NewRecorder()
	h.Delete(rec, req)

	assert.Equal(t, http.StatusNoContent, rec.Code)
}
```

- [ ] **Step 3: Run the tests and verify they fail**

Run: `cd services/s3 && go test ./internal/api/... -run TestObjectHandlers -v`
Expected: build failure — `api.ObjectHandlers` undefined.

- [ ] **Step 4: Write the implementation**

Create `services/s3/internal/api/object_handlers.go`:

```go
package api

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/aliffaizuddin/AWS/services/s3/internal/storage"
	"github.com/google/uuid"
)

// ObjectRepo is the subset of metadata.ObjectRepo's methods the object
// handlers need.
type ObjectRepo interface {
	Put(ctx context.Context, obj metadata.Object) error
	Get(ctx context.Context, bucket, key string) (*metadata.Object, error)
	Delete(ctx context.Context, bucket, key string) error
}

type ObjectHandlers struct {
	Buckets BucketRepo
	Objects ObjectRepo
	Store   storage.Store
}

func (h *ObjectHandlers) Put(w http.ResponseWriter, r *http.Request) {
	bucket := r.PathValue("bucket")
	key := r.PathValue("key")

	if _, err := h.Buckets.Get(r.Context(), bucket); errors.Is(err, metadata.ErrBucketNotFound) {
		WriteS3Error(w, ErrNoSuchBucket, bucket)
		return
	} else if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, bucket)
		return
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, key)
		return
	}

	sum := md5.Sum(body)
	etag := hex.EncodeToString(sum[:])
	storageID := uuid.New()

	if err := h.Store.Put(r.Context(), storageID, bytes.NewReader(body)); err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, key)
		return
	}

	contentType := r.Header.Get("Content-Type")
	if contentType == "" {
		contentType = "application/octet-stream"
	}

	obj := metadata.Object{
		BucketName:  bucket,
		Key:         key,
		ContentType: contentType,
		SizeBytes:   int64(len(body)),
		ETag:        etag,
		StorageID:   storageID,
	}
	if err := h.Objects.Put(r.Context(), obj); err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, key)
		return
	}

	w.Header().Set("ETag", etag)
	w.WriteHeader(http.StatusOK)
}

func (h *ObjectHandlers) Get(w http.ResponseWriter, r *http.Request) {
	bucket := r.PathValue("bucket")
	key := r.PathValue("key")

	obj, err := h.Objects.Get(r.Context(), bucket, key)
	if errors.Is(err, metadata.ErrObjectNotFound) {
		WriteS3Error(w, ErrNoSuchKey, key)
		return
	}
	if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, key)
		return
	}

	blob, err := h.Store.Get(r.Context(), obj.StorageID)
	if err != nil {
		WriteS3Error(w, S3Error{Code: "InternalError", Message: err.Error()}, key)
		return
	}
	defer blob.Close()

	writeObjectHeaders(w, obj)
	w.WriteHeader(http.StatusOK)
	_, _ = io.Copy(w, blob)
}

func (h *ObjectHandlers) Head(w http.ResponseWriter, r *http.Request) {
	bucket := r.PathValue("bucket")
	key := r.PathValue("key")

	obj, err := h.Objects.Get(r.Context(), bucket, key)
	if errors.Is(err, metadata.ErrObjectNotFound) {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	writeObjectHeaders(w, obj)
	w.WriteHeader(http.StatusOK)
}

// Delete is idempotent, matching real S3: it always returns 204, whether
// or not the key existed.
func (h *ObjectHandlers) Delete(w http.ResponseWriter, r *http.Request) {
	bucket := r.PathValue("bucket")
	key := r.PathValue("key")

	obj, err := h.Objects.Get(r.Context(), bucket, key)
	if err == nil {
		_ = h.Store.Delete(r.Context(), obj.StorageID)
		_ = h.Objects.Delete(r.Context(), bucket, key)
	}

	w.WriteHeader(http.StatusNoContent)
}

func writeObjectHeaders(w http.ResponseWriter, obj *metadata.Object) {
	w.Header().Set("Content-Type", obj.ContentType)
	w.Header().Set("Content-Length", strconv.FormatInt(obj.SizeBytes, 10))
	w.Header().Set("ETag", obj.ETag)
	w.Header().Set("Last-Modified", obj.CreatedAt.UTC().Format(http.TimeFormat))
}
```

- [ ] **Step 5: Run the tests and verify they pass**

Run: `cd services/s3 && go test ./internal/api/... -v`
Expected: all tests in the package PASS (bucket handlers + object handlers + xml/error tests).

- [ ] **Step 6: Commit**

```bash
git add services/s3/internal/api/
git commit -m "feat: add object HTTP handlers"
```

---

### Task 7: Router, health check, and main.go wiring

**Files:**
- Create: `services/s3/internal/api/router.go`
- Create: `services/s3/internal/api/healthz.go`
- Create: `services/s3/cmd/s3/main.go`
- Test: `services/s3/internal/api/healthz_test.go`
- Test: `services/s3/cmd/s3/integration_test.go`

**Interfaces:**
- Consumes: everything from Tasks 1-6: `storage.NewDiskStore`, `metadata.Connect`, `metadata.NewBucketRepo`, `metadata.NewObjectRepo`, `api.BucketHandlers`, `api.ObjectHandlers`.
- Produces: `api.NewHealthzHandler(ping func(ctx context.Context) error) http.HandlerFunc`; `api.NewRouter(bucketH *BucketHandlers, objectH *ObjectHandlers, healthz http.HandlerFunc) *http.ServeMux`; the `services/s3` binary itself, reading `S3_DB_DSN`, `S3_DATA_DIR`, `S3_LISTEN_ADDR` from the environment.

- [ ] **Step 1: Write the failing healthz test**

Create `services/s3/internal/api/healthz_test.go`:

```go
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
```

- [ ] **Step 2: Run it and verify it fails**

Run: `cd services/s3 && go test ./internal/api/... -run TestHealthz -v`
Expected: build failure — `api.NewHealthzHandler` undefined.

- [ ] **Step 3: Implement healthz and the router**

Create `services/s3/internal/api/healthz.go`:

```go
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
```

Create `services/s3/internal/api/router.go`:

```go
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
```

- [ ] **Step 4: Run it and verify it passes**

Run: `cd services/s3 && go test ./internal/api/... -v`
Expected: all tests in the package PASS.

- [ ] **Step 5: Commit the router and healthz**

```bash
git add services/s3/internal/api/router.go services/s3/internal/api/healthz.go services/s3/internal/api/healthz_test.go
git commit -m "feat: add router and health check endpoint"
```

- [ ] **Step 6: Write the failing end-to-end integration test**

Create `services/s3/cmd/s3/integration_test.go`:

```go
package main_test

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/aliffaizuddin/AWS/services/s3/internal/api"
	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/aliffaizuddin/AWS/services/s3/internal/storage"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/stretchr/testify/require"
)

// TestS3Service_EndToEnd exercises the full stack — real Postgres (via
// testcontainers), a real DiskStore (temp dir), and the real HTTP router —
// through the create-bucket -> put-object -> get-object -> delete-object ->
// delete-bucket lifecycle.
func TestS3Service_EndToEnd(t *testing.T) {
	ctx := context.Background()

	container, err := tcpostgres.Run(ctx, "postgres:16-alpine",
		tcpostgres.WithDatabase("s3e2e"),
		tcpostgres.WithUsername("s3e2e"),
		tcpostgres.WithPassword("s3e2e"),
	)
	require.NoError(t, err)
	t.Cleanup(func() { _ = container.Terminate(ctx) })

	dsn, err := container.ConnectionString(ctx, "sslmode=disable")
	require.NoError(t, err)

	db, err := metadata.Connect(ctx, dsn)
	require.NoError(t, err)
	t.Cleanup(func() { _ = db.Close() })

	store, err := storage.NewDiskStore(t.TempDir())
	require.NoError(t, err)

	buckets := metadata.NewBucketRepo(db)
	objects := metadata.NewObjectRepo(db)

	bucketH := &api.BucketHandlers{Buckets: buckets, Objects: objects}
	objectH := &api.ObjectHandlers{Buckets: bucketH.Buckets, Objects: objects, Store: store}
	router := api.NewRouter(bucketH, objectH, api.NewHealthzHandler(func(ctx context.Context) error {
		return db.PingContext(ctx)
	}))

	srv := httptest.NewServer(router)
	defer srv.Close()

	client := srv.Client()

	// Create bucket.
	req, _ := http.NewRequest(http.MethodPut, srv.URL+"/e2e-bucket", nil)
	resp, err := client.Do(req)
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	resp.Body.Close()

	// Put object.
	req, _ = http.NewRequest(http.MethodPut, srv.URL+"/e2e-bucket/hello.txt", strings.NewReader("hello world"))
	req.Header.Set("Content-Type", "text/plain")
	resp, err = client.Do(req)
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	resp.Body.Close()

	// Get object back.
	resp, err = client.Get(srv.URL + "/e2e-bucket/hello.txt")
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	body, err := io.ReadAll(resp.Body)
	require.NoError(t, err)
	resp.Body.Close()
	require.Equal(t, "hello world", string(body))
	require.Equal(t, "text/plain", resp.Header.Get("Content-Type"))

	// Delete object.
	req, _ = http.NewRequest(http.MethodDelete, srv.URL+"/e2e-bucket/hello.txt", nil)
	resp, err = client.Do(req)
	require.NoError(t, err)
	require.Equal(t, http.StatusNoContent, resp.StatusCode)
	resp.Body.Close()

	// Delete (now-empty) bucket.
	req, _ = http.NewRequest(http.MethodDelete, srv.URL+"/e2e-bucket", nil)
	resp, err = client.Do(req)
	require.NoError(t, err)
	require.Equal(t, http.StatusNoContent, resp.StatusCode)
	resp.Body.Close()
}
```

Add `"strings"` to this file's import block.

- [ ] **Step 7: Run it and verify it fails**

Run: `cd services/s3 && go test ./cmd/s3/... -v`
Expected: build failure — `main_test` package can't resolve because `cmd/s3` has no non-test `.go` file yet (no `main` package declared).

- [ ] **Step 8: Write main.go**

Create `services/s3/cmd/s3/main.go`:

```go
package main

import (
	"context"
	"log"
	"net/http"
	"os"

	"github.com/aliffaizuddin/AWS/services/s3/internal/api"
	"github.com/aliffaizuddin/AWS/services/s3/internal/metadata"
	"github.com/aliffaizuddin/AWS/services/s3/internal/storage"
)

func main() {
	ctx := context.Background()

	dsn := requireEnv("S3_DB_DSN")
	dataDir := requireEnv("S3_DATA_DIR")
	listenAddr := envOrDefault("S3_LISTEN_ADDR", ":8080")

	db, err := metadata.Connect(ctx, dsn)
	if err != nil {
		log.Fatalf("connect to postgres: %v", err)
	}
	defer db.Close()

	store, err := storage.NewDiskStore(dataDir)
	if err != nil {
		log.Fatalf("init disk store: %v", err)
	}

	buckets := metadata.NewBucketRepo(db)
	objects := metadata.NewObjectRepo(db)

	bucketH := &api.BucketHandlers{Buckets: buckets, Objects: objects}
	objectH := &api.ObjectHandlers{Buckets: buckets, Objects: objects, Store: store}
	healthz := api.NewHealthzHandler(func(ctx context.Context) error {
		return db.PingContext(ctx)
	})

	router := api.NewRouter(bucketH, objectH, healthz)

	log.Printf("s3 service listening on %s", listenAddr)
	if err := http.ListenAndServe(listenAddr, router); err != nil {
		log.Fatalf("server error: %v", err)
	}
}

func requireEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		log.Fatalf("missing required env var %s", key)
	}
	return v
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
```

- [ ] **Step 9: Run the integration test and verify it passes**

Run: `cd services/s3 && go test ./cmd/s3/... -v`
Expected: `TestS3Service_EndToEnd` PASSes. (Requires Docker running locally.)

- [ ] **Step 10: Run the full test suite**

Run: `cd services/s3 && go test ./... -v`
Expected: every test across `storage`, `metadata`, `api`, and `cmd/s3` PASSes.

- [ ] **Step 11: Commit**

```bash
cd services/s3
go mod tidy
git add services/s3/
git commit -m "feat: wire S3 service main.go and add end-to-end integration test"
```

---

### Task 8: Local dev loop and docs update

**Files:**
- Create: `docker-compose.yml` (repo root)
- Modify: `docs/services/s3.md` (update Status)

**Interfaces:**
- Consumes: the `services/s3` binary built in Task 7 (via its Dockerfile — created in this task).
- Produces: `services/s3/Dockerfile`; root `docker-compose.yml` bringing up Postgres + the s3 service together.

- [ ] **Step 1: Write the Dockerfile**

Create `services/s3/Dockerfile`:

```dockerfile
FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /out/s3 ./cmd/s3

FROM alpine:3.20
COPY --from=build /out/s3 /usr/local/bin/s3
ENTRYPOINT ["/usr/local/bin/s3"]
```

- [ ] **Step 2: Write docker-compose.yml**

Create `docker-compose.yml` at the repo root:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: cloudlite
      POSTGRES_USER: cloudlite
      POSTGRES_PASSWORD: cloudlite
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U cloudlite"]
      interval: 5s
      timeout: 5s
      retries: 5

  s3:
    build:
      context: ./services/s3
    environment:
      S3_DB_DSN: "postgres://cloudlite:cloudlite@postgres:5432/cloudlite?sslmode=disable"
      S3_DATA_DIR: "/data"
      S3_LISTEN_ADDR: ":8080"
    ports:
      - "8080:8080"
    volumes:
      - s3-data:/data
    depends_on:
      postgres:
        condition: service_healthy

volumes:
  postgres-data:
  s3-data:
```

- [ ] **Step 3: Manually verify the compose stack**

Run:
```bash
docker compose up --build -d
curl -i http://localhost:8080/healthz
curl -i -X PUT http://localhost:8080/manual-test-bucket
curl -i -X PUT http://localhost:8080/manual-test-bucket/hello.txt -H "Content-Type: text/plain" -d "hello from curl"
curl -i http://localhost:8080/manual-test-bucket/hello.txt
curl -i -X DELETE http://localhost:8080/manual-test-bucket/hello.txt
curl -i -X DELETE http://localhost:8080/manual-test-bucket
docker compose down -v
```
Expected: `/healthz` → 200; bucket create → 200; put object → 200 with an `ETag` header; get object → 200 with body `hello from curl`; delete object → 204; delete bucket → 204.

- [ ] **Step 4: Update the service doc**

Modify `docs/services/s3.md`: change the `**Status:**` line at the top from `not yet built. Anchor service — build first, per architecture.md §11 (build order).` to:

```markdown
**Status:** Phase 1 (foundation) built — bucket CRUD and basic object
PUT/GET/DELETE/HEAD, wire-compatible with AWS S3's REST API (path-style,
no ranges/versioning/multipart yet). See
[`../superpowers/plans/2026-08-13-s3-clone-phase1.md`](../superpowers/plans/2026-08-13-s3-clone-phase1.md)
for what was built and
[`../superpowers/specs/2026-08-13-s3-clone-phase1-design.md`](../superpowers/specs/2026-08-13-s3-clone-phase1-design.md)
for the design. Phases 2-4 (byte-range GET + tags, versioning, multipart)
are not yet built.
```

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml services/s3/Dockerfile docs/services/s3.md
git commit -m "feat: add docker-compose local dev loop for S3 service"
```

---

## Self-Review Notes

- **Spec coverage:** every §7 API surface row (CreateBucket, ListBuckets, HeadBucket, DeleteBucket, PutObject, GetObject, HeadObject, DeleteObject) has a task + test. §6 schema matches Tasks 2-3 exactly. §8 error handling (XML shape, 4 error codes) is Task 4. §9 testing strategy (testify + testcontainers, unit + integration split) is used throughout. §10 local dev is Task 8.
- **Type consistency:** `metadata.Object`/`metadata.Bucket` field names are defined once in Task 2/3 and referenced identically in Tasks 4-7. `BucketRepo`/`ObjectRepo`/`ObjectLister` interfaces (Task 5/6) match `*metadata.BucketRepo`/`*metadata.ObjectRepo`'s actual method signatures (Task 2/3) exactly, so no adapter is needed when wiring real types into them in Task 7/main.go.
- **Fixed during self-review:** Task 6 originally worked around a perceived `bytes` double-import with a hand-rolled `byteSliceReader` — unnecessary, since `bytes` wasn't imported under any other name in that file. Replaced with `bytes.NewReader(body)` directly and removed the dead helper type. Also replaced `nil` context args in Task 6's tests with `context.Background()`.
