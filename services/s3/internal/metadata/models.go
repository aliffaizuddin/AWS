package metadata

import "time"

// Bucket is a single row in the buckets table.
type Bucket struct {
	Name      string    `db:"name"`
	CreatedAt time.Time `db:"created_at"`
}
