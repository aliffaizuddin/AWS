# S3 Clone Phase 1 — Java Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up `services/s3` as a working Spring Boot (Java 21) service delivering the same Phase 1 behavior as the shelved Go implementation on `feat/s3-clone-phase1` — bucket CRUD and object PUT/GET/DELETE/HEAD, wire-compatible with the real AWS S3 REST API, backed by Postgres metadata and UUID-addressed local disk storage.

**Architecture:** A Spring Boot application layered as `controller/` (thin HTTP binding) → `service/` (business logic: existence checks, orphan-blob cleanup, delete ordering) → `repository/` (Spring Data JPA over Postgres) + `storage/` (disk-backed blob store), with a shared `error/` package rendering AWS-shaped XML error bodies via a `@RestControllerAdvice`.

**Tech Stack:** Java 21, Spring Boot 3.3.4 (Spring MVC + virtual threads), Maven, Spring Data JPA + Hibernate, Flyway, `jackson-dataformat-xml`, JUnit 5 + Mockito + AssertJ, Testcontainers (`@ServiceConnection`).

**Spec:** [`docs/superpowers/specs/2026-08-19-s3-clone-phase1-java-design.md`](../specs/2026-08-19-s3-clone-phase1-java-design.md)

## Global Constraints

- Module root: `services/s3` — this is a fresh Maven project; `services/s3` does not exist on this branch (it was branched from `main`, which predates the Go implementation on `feat/s3-clone-phase1`/PR #6 — nothing to delete).
- Build tool: Maven, no wrapper. `groupId=dev.cloudlite`, `artifactId=s3`, `version=0.1.0`, package root `dev.cloudlite.s3`.
- Spring Boot 3.3.4 parent, Java 21. Spring MVC (not WebFlux). `spring.threads.virtual.enabled=true`.
- Persistence via Spring Data JPA + Hibernate; schema managed by Flyway migrations, never `hibernate.ddl-auto=update`.
- XML via `jackson-dataformat-xml` (`XmlMapper`/Jackson annotations), not JAXB.
- No auth in this phase. `PutObject` request bodies are capped at 100 MiB (`s3.EntityTooLarge`, mirroring the Go version's `maxObjectSize`).
- Path-style addressing only (`/{bucket}`, `/{bucket}/{key}`). No byte-range GET, versioning, multipart upload, or custom tags — out of scope per `docs/future-work.md`.
- This is a from-scratch Spring implementation of the Go version's *behavior*, not a mechanical line-for-line port — take Spring-idiomatic simplifications called out in the spec (e.g. no HEAD/routing workaround needed).
- Every task commits with a Conventional Commit message (`feat|test|build|docs`) per `docs/decisions/0012-commit-and-branch-conventions.md`.

---

## Task 1: Scaffold the Maven/Spring Boot project + `/healthz`

**Files:**
- Create: `services/s3/pom.xml`
- Create: `services/s3/src/main/resources/application.yml`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/S3Application.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/controller/HealthController.java`
- Test: `services/s3/src/test/java/dev/cloudlite/s3/controller/HealthControllerTest.java`

**Interfaces:**
- Produces: `HealthController` mapped to `GET /healthz`, constructor `HealthController(DataSource dataSource)`. Later tasks don't depend on this class directly.

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
    <relativePath/>
  </parent>

  <groupId>dev.cloudlite</groupId>
  <artifactId>s3</artifactId>
  <version>0.1.0</version>
  <name>s3</name>
  <description>CloudLite S3 clone service</description>

  <properties>
    <java.version>21</java.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.dataformat</groupId>
      <artifactId>jackson-dataformat-xml</artifactId>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-bom</artifactId>
        <version>1.20.2</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Create `application.yml`**

```yaml
server:
  port: ${SERVER_PORT:8080}

spring:
  application:
    name: s3
  threads:
    virtual:
      enabled: true
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/cloudlite}
    username: ${SPRING_DATASOURCE_USERNAME:cloudlite}
    password: ${SPRING_DATASOURCE_PASSWORD:cloudlite}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
  mvc:
    throw-exception-if-no-handler-found: true
  web:
    resources:
      add-mappings: false

s3:
  data-dir: ${S3_DATA_DIR:/data}
```

- [ ] **Step 3: Create the main application class**

```java
package dev.cloudlite.s3;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class S3Application {

    public static void main(String[] args) {
        SpringApplication.run(S3Application.class, args);
    }
}
```

- [ ] **Step 4: Write the failing test for `/healthz`**

`services/s3/src/test/java/dev/cloudlite/s3/controller/HealthControllerTest.java`:

```java
package dev.cloudlite.s3.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DataSource dataSource;

    @Test
    void healthzReturns200WhenTheDatabaseIsReachable() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);

        mockMvc.perform(get("/healthz")).andExpect(status().isOk());
    }

    @Test
    void healthzReturns503WhenTheDatabaseIsUnreachable() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        mockMvc.perform(get("/healthz")).andExpect(status().isServiceUnavailable());
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `cd services/s3 && mvn -q test -Dtest=HealthControllerTest`
Expected: FAIL — `HealthController` does not exist (compile error).

- [ ] **Step 6: Implement `HealthController`**

```java
package dev.cloudlite.s3.controller;

import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Void> healthz() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(2)) {
                return ResponseEntity.ok().build();
            }
        } catch (Exception e) {
            // fall through to 503
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd services/s3 && mvn -q test -Dtest=HealthControllerTest`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add services/s3/pom.xml services/s3/src/main/resources/application.yml \
  services/s3/src/main/java/dev/cloudlite/s3/S3Application.java \
  services/s3/src/main/java/dev/cloudlite/s3/controller/HealthController.java \
  services/s3/src/test/java/dev/cloudlite/s3/controller/HealthControllerTest.java
git commit -m "feat: scaffold s3 Spring Boot project with /healthz"
```

---

## Task 2: Disk-backed blob storage layer

**Files:**
- Create: `services/s3/src/main/java/dev/cloudlite/s3/storage/BlobStore.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/storage/BlobNotFoundException.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/storage/DiskBlobStore.java`
- Test: `services/s3/src/test/java/dev/cloudlite/s3/storage/DiskBlobStoreTest.java`

**Interfaces:**
- Produces: `BlobStore` interface — `void put(UUID id, InputStream in)`, `InputStream get(UUID id)`, `void delete(UUID id)` — `get`/`delete` throw `BlobNotFoundException` (unchecked) when `id` has no blob. `DiskBlobStore` is a `@Component` implementing it, constructed with `DiskBlobStore(@Value("${s3.data-dir}") String dataDir)`.
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Write the failing tests**

`services/s3/src/test/java/dev/cloudlite/s3/storage/DiskBlobStoreTest.java`:

```java
package dev.cloudlite.s3.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.StreamUtils;

class DiskBlobStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void putThenGetReturnsTheSameBytes() throws Exception {
        DiskBlobStore store = new DiskBlobStore(tempDir.toString());
        UUID id = UUID.randomUUID();

        store.put(id, new ByteArrayInputStream("hello".getBytes()));

        byte[] read = StreamUtils.copyToByteArray(store.get(id));
        assertThat(new String(read)).isEqualTo("hello");
    }

    @Test
    void getOnAMissingIdThrowsBlobNotFoundException() {
        DiskBlobStore store = new DiskBlobStore(tempDir.toString());

        assertThatThrownBy(() -> store.get(UUID.randomUUID()))
            .isInstanceOf(BlobNotFoundException.class);
    }

    @Test
    void deleteOnAMissingIdThrowsBlobNotFoundException() {
        DiskBlobStore store = new DiskBlobStore(tempDir.toString());

        assertThatThrownBy(() -> store.delete(UUID.randomUUID()))
            .isInstanceOf(BlobNotFoundException.class);
    }

    @Test
    void putOverwritesAnExistingBlob() throws Exception {
        DiskBlobStore store = new DiskBlobStore(tempDir.toString());
        UUID id = UUID.randomUUID();

        store.put(id, new ByteArrayInputStream("first".getBytes()));
        store.put(id, new ByteArrayInputStream("second".getBytes()));

        byte[] read = StreamUtils.copyToByteArray(store.get(id));
        assertThat(new String(read)).isEqualTo("second");
    }

    @Test
    void deleteRemovesTheBlobSoASubsequentGetFails() throws Exception {
        DiskBlobStore store = new DiskBlobStore(tempDir.toString());
        UUID id = UUID.randomUUID();
        store.put(id, new ByteArrayInputStream("hello".getBytes()));

        store.delete(id);

        assertThatThrownBy(() -> store.get(id)).isInstanceOf(BlobNotFoundException.class);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd services/s3 && mvn -q test -Dtest=DiskBlobStoreTest`
Expected: FAIL — `BlobStore`, `BlobNotFoundException`, `DiskBlobStore` do not exist.

- [ ] **Step 3: Implement `BlobStore`**

```java
package dev.cloudlite.s3.storage;

import java.io.InputStream;
import java.util.UUID;

public interface BlobStore {
    void put(UUID id, InputStream in);
    InputStream get(UUID id);
    void delete(UUID id);
}
```

- [ ] **Step 4: Implement `BlobNotFoundException`**

```java
package dev.cloudlite.s3.storage;

public class BlobNotFoundException extends RuntimeException {
    public BlobNotFoundException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Implement `DiskBlobStore`**

```java
package dev.cloudlite.s3.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DiskBlobStore implements BlobStore {

    private final Path dataDir;

    public DiskBlobStore(@Value("${s3.data-dir}") String dataDir) {
        this.dataDir = Path.of(dataDir);
        try {
            Files.createDirectories(this.dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("storage: create data dir " + dataDir, e);
        }
    }

    private Path pathFor(UUID id) {
        return dataDir.resolve(id.toString());
    }

    @Override
    public void put(UUID id, InputStream in) {
        Path finalPath = pathFor(id);
        Path tmpPath = dataDir.resolve(id + ".tmp");
        try (FileChannel channel = FileChannel.open(tmpPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            in.transferTo(Channels.newOutputStream(channel));
            channel.force(true);
        } catch (IOException e) {
            deleteQuietly(tmpPath);
            throw new UncheckedIOException("storage: write " + id, e);
        }
        try {
            Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(tmpPath);
            throw new UncheckedIOException("storage: rename " + id, e);
        }
    }

    @Override
    public InputStream get(UUID id) {
        try {
            return Files.newInputStream(pathFor(id));
        } catch (NoSuchFileException e) {
            throw new BlobNotFoundException("storage: blob not found: " + id);
        } catch (IOException e) {
            throw new UncheckedIOException("storage: open " + id, e);
        }
    }

    @Override
    public void delete(UUID id) {
        try {
            Files.delete(pathFor(id));
        } catch (NoSuchFileException e) {
            throw new BlobNotFoundException("storage: blob not found: " + id);
        } catch (IOException e) {
            throw new UncheckedIOException("storage: remove " + id, e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup of a partially written temp file
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd services/s3 && mvn -q test -Dtest=DiskBlobStoreTest`
Expected: PASS (all 5 tests)

- [ ] **Step 7: Commit**

```bash
git add services/s3/src/main/java/dev/cloudlite/s3/storage services/s3/src/test/java/dev/cloudlite/s3/storage
git commit -m "feat: add disk-backed blob storage layer"
```

---

## Task 3: Postgres schema (Flyway) + JPA entities + repositories

**Files:**
- Create: `services/s3/src/main/resources/db/migration/V1__create_buckets.sql`
- Create: `services/s3/src/main/resources/db/migration/V2__create_objects.sql`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/domain/Bucket.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/domain/ObjectMetadataId.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/domain/ObjectMetadata.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/repository/BucketRepository.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/repository/ObjectRepository.java`
- Test: `services/s3/src/test/java/dev/cloudlite/s3/repository/BucketRepositoryTest.java`
- Test: `services/s3/src/test/java/dev/cloudlite/s3/repository/ObjectRepositoryTest.java`

**Interfaces:**
- Produces: `Bucket(String name)` entity with `getName()`/`getCreatedAt()`; `ObjectMetadataId(String bucketName, String key)`; `ObjectMetadata(String bucketName, String key, String contentType, long sizeBytes, String etag, UUID storageId)` entity with `getId()`/`getBucketName()`/`getKey()`/`getContentType()`/`getSizeBytes()`/`getEtag()`/`getStorageId()`/`getCreatedAt()`. `BucketRepository extends JpaRepository<Bucket, String>` with `List<Bucket> findAllByOrderByNameAsc()`. `ObjectRepository extends JpaRepository<ObjectMetadata, ObjectMetadataId>` with `boolean existsByIdBucketName(String bucketName)`.
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Create the Flyway migrations**

`services/s3/src/main/resources/db/migration/V1__create_buckets.sql`:

```sql
CREATE TABLE buckets (
    name       TEXT PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`services/s3/src/main/resources/db/migration/V2__create_objects.sql`:

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

- [ ] **Step 2: Create the `Bucket` and `ObjectMetadata` entities**

`services/s3/src/main/java/dev/cloudlite/s3/domain/Bucket.java`:

```java
package dev.cloudlite.s3.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "buckets")
public class Bucket {

    @Id
    private String name;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Bucket() {
        // for JPA
    }

    public Bucket(String name) {
        this.name = name;
        this.createdAt = OffsetDateTime.now();
    }

    public String getName() {
        return name;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
```

`services/s3/src/main/java/dev/cloudlite/s3/domain/ObjectMetadataId.java`:

```java
package dev.cloudlite.s3.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ObjectMetadataId implements Serializable {

    private String bucketName;
    private String key;

    protected ObjectMetadataId() {
        // for JPA
    }

    public ObjectMetadataId(String bucketName, String key) {
        this.bucketName = bucketName;
        this.key = key;
    }

    public String getBucketName() {
        return bucketName;
    }

    public String getKey() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ObjectMetadataId that)) {
            return false;
        }
        return Objects.equals(bucketName, that.bucketName) && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bucketName, key);
    }
}
```

`services/s3/src/main/java/dev/cloudlite/s3/domain/ObjectMetadata.java`:

```java
package dev.cloudlite.s3.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "objects")
public class ObjectMetadata {

    @EmbeddedId
    @AttributeOverride(name = "bucketName", column = @Column(name = "bucket_name"))
    @AttributeOverride(name = "key", column = @Column(name = "key"))
    private ObjectMetadataId id;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String etag;

    @Column(name = "storage_id", nullable = false)
    private UUID storageId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ObjectMetadata() {
        // for JPA
    }

    public ObjectMetadata(String bucketName, String key, String contentType, long sizeBytes, String etag, UUID storageId) {
        this.id = new ObjectMetadataId(bucketName, key);
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.etag = etag;
        this.storageId = storageId;
        this.createdAt = OffsetDateTime.now();
    }

    public ObjectMetadataId getId() {
        return id;
    }

    public String getBucketName() {
        return id.getBucketName();
    }

    public String getKey() {
        return id.getKey();
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getEtag() {
        return etag;
    }

    public UUID getStorageId() {
        return storageId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
```

- [ ] **Step 3: Create the repositories**

`services/s3/src/main/java/dev/cloudlite/s3/repository/BucketRepository.java`:

```java
package dev.cloudlite.s3.repository;

import dev.cloudlite.s3.domain.Bucket;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BucketRepository extends JpaRepository<Bucket, String> {

    List<Bucket> findAllByOrderByNameAsc();
}
```

`services/s3/src/main/java/dev/cloudlite/s3/repository/ObjectRepository.java`:

```java
package dev.cloudlite.s3.repository;

import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.domain.ObjectMetadataId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObjectRepository extends JpaRepository<ObjectMetadata, ObjectMetadataId> {

    boolean existsByIdBucketName(String bucketName);
}
```

- [ ] **Step 4: Write the repository tests (Testcontainers, real Postgres)**

`services/s3/src/test/java/dev/cloudlite/s3/repository/BucketRepositoryTest.java`:

```java
package dev.cloudlite.s3.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cloudlite.s3.domain.Bucket;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BucketRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private BucketRepository bucketRepository;

    @Test
    void savedBucketCanBeFoundById() {
        bucketRepository.save(new Bucket("archive"));

        assertThat(bucketRepository.findById("archive")).isPresent();
    }

    @Test
    void findAllByOrderByNameAscReturnsBucketsSortedByName() {
        bucketRepository.save(new Bucket("zebra"));
        bucketRepository.save(new Bucket("alpha"));

        List<Bucket> found = bucketRepository.findAllByOrderByNameAsc();

        assertThat(found).extracting(Bucket::getName).containsExactly("alpha", "zebra");
    }

    @Test
    void deleteByIdRemovesTheBucket() {
        bucketRepository.save(new Bucket("temp"));

        bucketRepository.deleteById("temp");

        assertThat(bucketRepository.existsById("temp")).isFalse();
    }
}
```

`services/s3/src/test/java/dev/cloudlite/s3/repository/ObjectRepositoryTest.java`:

```java
package dev.cloudlite.s3.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cloudlite.s3.domain.Bucket;
import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.domain.ObjectMetadataId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ObjectRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private BucketRepository bucketRepository;

    @Autowired
    private ObjectRepository objectRepository;

    @Test
    void savedObjectCanBeFoundByCompositeId() {
        bucketRepository.save(new Bucket("photos"));
        objectRepository.save(new ObjectMetadata("photos", "cat.png", "image/png", 42L, "abc123", UUID.randomUUID()));

        var found = objectRepository.findById(new ObjectMetadataId("photos", "cat.png"));

        assertThat(found).isPresent();
        assertThat(found.get().getEtag()).isEqualTo("abc123");
    }

    @Test
    void savingWithTheSameCompositeIdOverwritesTheExistingRow() {
        bucketRepository.save(new Bucket("photos"));
        objectRepository.save(new ObjectMetadata("photos", "cat.png", "image/png", 42L, "first-etag", UUID.randomUUID()));
        objectRepository.save(new ObjectMetadata("photos", "cat.png", "image/png", 99L, "second-etag", UUID.randomUUID()));

        var found = objectRepository.findById(new ObjectMetadataId("photos", "cat.png"));

        assertThat(found).isPresent();
        assertThat(found.get().getEtag()).isEqualTo("second-etag");
        assertThat(found.get().getSizeBytes()).isEqualTo(99L);
    }

    @Test
    void existsByIdBucketNameIsFalseForAnEmptyBucket() {
        bucketRepository.save(new Bucket("empty-bucket"));

        assertThat(objectRepository.existsByIdBucketName("empty-bucket")).isFalse();
    }

    @Test
    void existsByIdBucketNameIsTrueOnceAnObjectExists() {
        bucketRepository.save(new Bucket("photos2"));
        objectRepository.save(new ObjectMetadata("photos2", "cat.png", "image/png", 42L, "abc123", UUID.randomUUID()));

        assertThat(objectRepository.existsByIdBucketName("photos2")).isTrue();
    }

    @Test
    void deleteByIdRemovesTheObject() {
        bucketRepository.save(new Bucket("photos3"));
        objectRepository.save(new ObjectMetadata("photos3", "cat.png", "image/png", 42L, "abc123", UUID.randomUUID()));

        objectRepository.deleteById(new ObjectMetadataId("photos3", "cat.png"));

        assertThat(objectRepository.existsById(new ObjectMetadataId("photos3", "cat.png"))).isFalse();
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `cd services/s3 && mvn -q test -Dtest=BucketRepositoryTest,ObjectRepositoryTest`
Expected: PASS (requires Docker for Testcontainers). This is the first run since the migrations, entities, and repositories are written together — confirm the Flyway migrations apply cleanly against a real Postgres container and all assertions pass.

- [ ] **Step 6: Commit**

```bash
git add services/s3/src/main/resources/db/migration services/s3/src/main/java/dev/cloudlite/s3/domain \
  services/s3/src/main/java/dev/cloudlite/s3/repository services/s3/src/test/java/dev/cloudlite/s3/repository
git commit -m "feat: add Postgres schema, JPA entities, and repositories"
```

---

## Task 4: AWS-shaped error handling

**Files:**
- Create: `services/s3/src/main/java/dev/cloudlite/s3/error/S3ErrorCode.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/error/S3ApiException.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/error/S3ErrorResponse.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/error/GlobalExceptionHandler.java`
- Test: `services/s3/src/test/java/dev/cloudlite/s3/error/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `S3ErrorCode` enum (`NO_SUCH_BUCKET`, `NO_SUCH_KEY`, `BUCKET_ALREADY_EXISTS`, `BUCKET_NOT_EMPTY`, `INVALID_BUCKET_NAME`, `METHOD_NOT_ALLOWED`, `NOT_FOUND`, `ENTITY_TOO_LARGE`, `INTERNAL_ERROR`) with `code()`, `status()`, `defaultMessage()`. `S3ApiException(S3ErrorCode errorCode, String resource)` — unchecked, with `getErrorCode()`/`getResource()`. `GlobalExceptionHandler` is a `@RestControllerAdvice` that renders `S3ApiException`, `NoHandlerFoundException`, `HttpRequestMethodNotSupportedException`, and any other `Exception` as AWS-shaped XML.
- Consumes: nothing from earlier tasks (this is pure cross-cutting infrastructure that Tasks 5–6 depend on).

- [ ] **Step 1: Write the failing test**

`services/s3/src/test/java/dev/cloudlite/s3/error/GlobalExceptionHandlerTest.java`:

```java
package dev.cloudlite.s3.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void s3ApiExceptionIsRenderedAsAwsShapedXmlWithTheRightStatus() {
        S3ApiException ex = new S3ApiException(S3ErrorCode.NO_SUCH_BUCKET, "photos");

        ResponseEntity<S3ErrorResponse> response = handler.handleS3ApiException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/xml");
        assertThat(response.getBody().getCode()).isEqualTo("NoSuchBucket");
        assertThat(response.getBody().getResource()).isEqualTo("photos");
        assertThat(response.getBody().getRequestId()).isNotBlank();
    }

    @Test
    void bucketAlreadyExistsMapsTo409() {
        S3ApiException ex = new S3ApiException(S3ErrorCode.BUCKET_ALREADY_EXISTS, "photos");

        ResponseEntity<S3ErrorResponse> response = handler.handleS3ApiException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void noHandlerFoundIsRenderedAsAGenericNotFoundError() {
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/no/such/route", new HttpHeaders());

        ResponseEntity<S3ErrorResponse> response = handler.handleNoHandlerFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo("NotFound");
    }

    @Test
    void methodNotSupportedIsRenderedAs405() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("POST");

        ResponseEntity<S3ErrorResponse> response = handler.handleMethodNotSupported(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().getCode()).isEqualTo("MethodNotAllowed");
    }

    @Test
    void unexpectedExceptionIsRenderedAsAGenericNonLeakyInternalError() {
        Exception ex = new RuntimeException("column \"foo\" does not exist");

        ResponseEntity<S3ErrorResponse> response = handler.handleUnexpected(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getCode()).isEqualTo("InternalError");
        assertThat(response.getBody().getMessage()).doesNotContain("column");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd services/s3 && mvn -q test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — `S3ErrorCode`, `S3ApiException`, `S3ErrorResponse`, `GlobalExceptionHandler` do not exist.

- [ ] **Step 3: Implement `S3ErrorCode`**

```java
package dev.cloudlite.s3.error;

import org.springframework.http.HttpStatus;

public enum S3ErrorCode {
    NO_SUCH_BUCKET("NoSuchBucket", HttpStatus.NOT_FOUND, "The specified bucket does not exist"),
    NO_SUCH_KEY("NoSuchKey", HttpStatus.NOT_FOUND, "The specified key does not exist"),
    BUCKET_ALREADY_EXISTS("BucketAlreadyExists", HttpStatus.CONFLICT, "The requested bucket name is not available"),
    BUCKET_NOT_EMPTY("BucketNotEmpty", HttpStatus.CONFLICT, "The bucket you tried to delete is not empty"),
    INVALID_BUCKET_NAME("InvalidBucketName", HttpStatus.BAD_REQUEST, "The specified bucket name is not valid"),
    METHOD_NOT_ALLOWED("MethodNotAllowed", HttpStatus.METHOD_NOT_ALLOWED, "The specified method is not allowed against this resource"),
    NOT_FOUND("NotFound", HttpStatus.NOT_FOUND, "The specified resource does not exist"),
    ENTITY_TOO_LARGE("EntityTooLarge", HttpStatus.BAD_REQUEST, "Your proposed upload exceeds the maximum allowed size"),
    INTERNAL_ERROR("InternalError", HttpStatus.INTERNAL_SERVER_ERROR, "We encountered an internal error. Please try again.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    S3ErrorCode(String code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
```

- [ ] **Step 4: Implement `S3ApiException`**

```java
package dev.cloudlite.s3.error;

public class S3ApiException extends RuntimeException {

    private final S3ErrorCode errorCode;
    private final String resource;

    public S3ApiException(S3ErrorCode errorCode, String resource) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.resource = resource;
    }

    public S3ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getResource() {
        return resource;
    }
}
```

- [ ] **Step 5: Implement `S3ErrorResponse`**

```java
package dev.cloudlite.s3.error;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JacksonXmlRootElement(localName = "Error")
public class S3ErrorResponse {

    @JacksonXmlProperty(localName = "Code")
    private final String code;

    @JacksonXmlProperty(localName = "Message")
    private final String message;

    @JacksonXmlProperty(localName = "Resource")
    private final String resource;

    @JacksonXmlProperty(localName = "RequestId")
    private final String requestId;

    public S3ErrorResponse(String code, String message, String resource, String requestId) {
        this.code = code;
        this.message = message;
        this.resource = resource;
        this.requestId = requestId;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getResource() {
        return resource;
    }

    public String getRequestId() {
        return requestId;
    }
}
```

- [ ] **Step 6: Implement `GlobalExceptionHandler`**

```java
package dev.cloudlite.s3.error;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(S3ApiException.class)
    public ResponseEntity<S3ErrorResponse> handleS3ApiException(S3ApiException ex) {
        return errorResponse(ex.getErrorCode(), ex.getResource());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<S3ErrorResponse> handleNoHandlerFound(NoHandlerFoundException ex) {
        return errorResponse(S3ErrorCode.NOT_FOUND, ex.getRequestURL());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<S3ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return errorResponse(S3ErrorCode.METHOD_NOT_ALLOWED, "");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<S3ErrorResponse> handleUnexpected(Exception ex) {
        log.error("s3: internal error", ex);
        return errorResponse(S3ErrorCode.INTERNAL_ERROR, "");
    }

    private ResponseEntity<S3ErrorResponse> errorResponse(S3ErrorCode code, String resource) {
        S3ErrorResponse body = new S3ErrorResponse(code.code(), code.defaultMessage(), resource, UUID.randomUUID().toString());
        return ResponseEntity.status(code.status())
            .contentType(MediaType.APPLICATION_XML)
            .body(body);
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd services/s3 && mvn -q test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add services/s3/src/main/java/dev/cloudlite/s3/error services/s3/src/test/java/dev/cloudlite/s3/error
git commit -m "feat: add AWS-shaped XML error handling"
```

---

## Task 5: Bucket service + controller

**Files:**
- Create: `services/s3/src/main/java/dev/cloudlite/s3/dto/OwnerXml.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/dto/BucketXml.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/dto/ListAllMyBucketsResultXml.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/service/BucketService.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/controller/BucketController.java`
- Test: `services/s3/src/test/java/dev/cloudlite/s3/service/BucketServiceTest.java`
- Test: `services/s3/src/test/java/dev/cloudlite/s3/controller/BucketControllerTest.java`

**Interfaces:**
- Consumes: `BucketRepository`, `ObjectRepository` (Task 3); `S3ApiException`, `S3ErrorCode`, `GlobalExceptionHandler` (Task 4).
- Produces: `BucketService(BucketRepository buckets, ObjectRepository objects)` with `void create(String name)`, `List<Bucket> list()`, `boolean exists(String name)`, `void delete(String name)`. `BucketController` mapped to `PUT /{bucket}`, `GET /`, `HEAD /{bucket}`, `DELETE /{bucket}`.

- [ ] **Step 1: Create the XML response DTOs**

`services/s3/src/main/java/dev/cloudlite/s3/dto/OwnerXml.java`:

```java
package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

public class OwnerXml {

    @JacksonXmlProperty(localName = "ID")
    private final String id;

    @JacksonXmlProperty(localName = "DisplayName")
    private final String displayName;

    public OwnerXml(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }
}
```

`services/s3/src/main/java/dev/cloudlite/s3/dto/BucketXml.java`:

```java
package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.time.OffsetDateTime;

public class BucketXml {

    @JacksonXmlProperty(localName = "Name")
    private final String name;

    @JacksonXmlProperty(localName = "CreationDate")
    private final OffsetDateTime creationDate;

    public BucketXml(String name, OffsetDateTime creationDate) {
        this.name = name;
        this.creationDate = creationDate;
    }

    public String getName() {
        return name;
    }

    public OffsetDateTime getCreationDate() {
        return creationDate;
    }
}
```

`services/s3/src/main/java/dev/cloudlite/s3/dto/ListAllMyBucketsResultXml.java`:

```java
package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;

@JacksonXmlRootElement(localName = "ListAllMyBucketsResult")
public class ListAllMyBucketsResultXml {

    @JacksonXmlProperty(localName = "Owner")
    private final OwnerXml owner;

    @JacksonXmlElementWrapper(localName = "Buckets")
    @JacksonXmlProperty(localName = "Bucket")
    private final List<BucketXml> buckets;

    public ListAllMyBucketsResultXml(OwnerXml owner, List<BucketXml> buckets) {
        this.owner = owner;
        this.buckets = buckets;
    }

    public OwnerXml getOwner() {
        return owner;
    }

    public List<BucketXml> getBuckets() {
        return buckets;
    }
}
```

- [ ] **Step 2: Write the failing `BucketService` unit test**

`services/s3/src/test/java/dev/cloudlite/s3/service/BucketServiceTest.java`:

```java
package dev.cloudlite.s3.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cloudlite.s3.domain.Bucket;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.repository.BucketRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BucketServiceTest {

    private BucketRepository buckets;
    private ObjectRepository objects;
    private BucketService service;

    @BeforeEach
    void setUp() {
        buckets = mock(BucketRepository.class);
        objects = mock(ObjectRepository.class);
        service = new BucketService(buckets, objects);
    }

    @Test
    void createRejectsTheEmptyBucketName() {
        assertThatThrownBy(() -> service.create(""))
            .isInstanceOf(S3ApiException.class)
            .extracting(e -> ((S3ApiException) e).getErrorCode())
            .isEqualTo(S3ErrorCode.INVALID_BUCKET_NAME);
    }

    @Test
    void createRejectsTheReservedHealthzName() {
        assertThatThrownBy(() -> service.create("healthz"))
            .isInstanceOf(S3ApiException.class)
            .extracting(e -> ((S3ApiException) e).getErrorCode())
            .isEqualTo(S3ErrorCode.INVALID_BUCKET_NAME);
    }

    @Test
    void createRejectsADuplicateBucketName() {
        when(buckets.existsById("photos")).thenReturn(true);

        assertThatThrownBy(() -> service.create("photos"))
            .isInstanceOf(S3ApiException.class)
            .extracting(e -> ((S3ApiException) e).getErrorCode())
            .isEqualTo(S3ErrorCode.BUCKET_ALREADY_EXISTS);
    }

    @Test
    void createSavesANewBucket() {
        when(buckets.existsById("photos")).thenReturn(false);

        service.create("photos");

        ArgumentCaptor<Bucket> captor = ArgumentCaptor.forClass(Bucket.class);
        verify(buckets).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("photos");
    }

    @Test
    void deleteRejectsANonEmptyBucket() {
        when(objects.existsByIdBucketName("photos")).thenReturn(true);

        assertThatThrownBy(() -> service.delete("photos"))
            .isInstanceOf(S3ApiException.class)
            .extracting(e -> ((S3ApiException) e).getErrorCode())
            .isEqualTo(S3ErrorCode.BUCKET_NOT_EMPTY);
    }

    @Test
    void deleteRejectsAMissingBucket() {
        when(objects.existsByIdBucketName("photos")).thenReturn(false);
        when(buckets.existsById("photos")).thenReturn(false);

        assertThatThrownBy(() -> service.delete("photos"))
            .isInstanceOf(S3ApiException.class)
            .extracting(e -> ((S3ApiException) e).getErrorCode())
            .isEqualTo(S3ErrorCode.NO_SUCH_BUCKET);
    }

    @Test
    void listReturnsBucketsFromTheRepository() {
        when(buckets.findAllByOrderByNameAsc()).thenReturn(List.of(new Bucket("alpha")));

        assertThat(service.list()).extracting(Bucket::getName).containsExactly("alpha");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd services/s3 && mvn -q test -Dtest=BucketServiceTest`
Expected: FAIL — `BucketService` does not exist.

- [ ] **Step 4: Implement `BucketService`**

```java
package dev.cloudlite.s3.service;

import dev.cloudlite.s3.domain.Bucket;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.repository.BucketRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class BucketService {

    private final BucketRepository buckets;
    private final ObjectRepository objects;

    public BucketService(BucketRepository buckets, ObjectRepository objects) {
        this.buckets = buckets;
        this.objects = objects;
    }

    public void create(String name) {
        if (name == null || name.isEmpty() || name.equals("healthz")) {
            throw new S3ApiException(S3ErrorCode.INVALID_BUCKET_NAME, name);
        }
        if (buckets.existsById(name)) {
            throw new S3ApiException(S3ErrorCode.BUCKET_ALREADY_EXISTS, name);
        }
        buckets.save(new Bucket(name));
    }

    public List<Bucket> list() {
        return buckets.findAllByOrderByNameAsc();
    }

    public boolean exists(String name) {
        return buckets.existsById(name);
    }

    public void delete(String name) {
        if (objects.existsByIdBucketName(name)) {
            throw new S3ApiException(S3ErrorCode.BUCKET_NOT_EMPTY, name);
        }
        if (!buckets.existsById(name)) {
            throw new S3ApiException(S3ErrorCode.NO_SUCH_BUCKET, name);
        }
        buckets.deleteById(name);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd services/s3 && mvn -q test -Dtest=BucketServiceTest`
Expected: PASS

- [ ] **Step 6: Write the failing `BucketController` MockMvc test**

`services/s3/src/test/java/dev/cloudlite/s3/controller/BucketControllerTest.java`:

```java
package dev.cloudlite.s3.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.s3.domain.Bucket;
import dev.cloudlite.s3.error.GlobalExceptionHandler;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.service.BucketService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BucketController.class)
@Import(GlobalExceptionHandler.class)
class BucketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BucketService bucketService;

    @Test
    void createReturns200OnSuccess() throws Exception {
        mockMvc.perform(put("/photos")).andExpect(status().isOk());
    }

    @Test
    void createReturns409WithAnXmlBodyWhenTheBucketAlreadyExists() throws Exception {
        doThrow(new S3ApiException(S3ErrorCode.BUCKET_ALREADY_EXISTS, "photos"))
            .when(bucketService).create("photos");

        mockMvc.perform(put("/photos"))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith("application/xml"))
            .andExpect(content().string(containsString("BucketAlreadyExists")));
    }

    @Test
    void listReturns200WithAnXmlBody() throws Exception {
        given(bucketService.list()).willReturn(List.of(new Bucket("photos")));

        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/xml"))
            .andExpect(content().string(containsString("photos")));
    }

    @Test
    void headReturns200WhenTheBucketExists() throws Exception {
        given(bucketService.exists("photos")).willReturn(true);

        mockMvc.perform(head("/photos")).andExpect(status().isOk());
    }

    @Test
    void headReturns404WithNoBodyWhenTheBucketIsMissing() throws Exception {
        given(bucketService.exists("missing")).willReturn(false);

        mockMvc.perform(head("/missing"))
            .andExpect(status().isNotFound())
            .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void deleteReturns204OnSuccess() throws Exception {
        mockMvc.perform(delete("/photos")).andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns409WhenTheBucketIsNotEmpty() throws Exception {
        doThrow(new S3ApiException(S3ErrorCode.BUCKET_NOT_EMPTY, "photos"))
            .when(bucketService).delete("photos");

        mockMvc.perform(delete("/photos")).andExpect(status().isConflict());
    }
}
```

- [ ] **Step 7: Run the test to verify it fails**

Run: `cd services/s3 && mvn -q test -Dtest=BucketControllerTest`
Expected: FAIL — `BucketController` does not exist.

- [ ] **Step 8: Implement `BucketController`**

```java
package dev.cloudlite.s3.controller;

import dev.cloudlite.s3.dto.BucketXml;
import dev.cloudlite.s3.dto.ListAllMyBucketsResultXml;
import dev.cloudlite.s3.dto.OwnerXml;
import dev.cloudlite.s3.service.BucketService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BucketController {

    private final BucketService bucketService;

    public BucketController(BucketService bucketService) {
        this.bucketService = bucketService;
    }

    @PutMapping("/{bucket}")
    public ResponseEntity<Void> create(@PathVariable String bucket) {
        bucketService.create(bucket);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/")
    public ResponseEntity<ListAllMyBucketsResultXml> list() {
        List<BucketXml> bucketXmls = bucketService.list().stream()
            .map(b -> new BucketXml(b.getName(), b.getCreatedAt()))
            .toList();
        ListAllMyBucketsResultXml body =
            new ListAllMyBucketsResultXml(new OwnerXml("cloudlite", "cloudlite"), bucketXmls);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(body);
    }

    @RequestMapping(path = "/{bucket}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(@PathVariable String bucket) {
        return bucketService.exists(bucket) ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{bucket}")
    public ResponseEntity<Void> delete(@PathVariable String bucket) {
        bucketService.delete(bucket);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `cd services/s3 && mvn -q test -Dtest=BucketServiceTest,BucketControllerTest`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add services/s3/src/main/java/dev/cloudlite/s3/dto services/s3/src/main/java/dev/cloudlite/s3/service/BucketService.java \
  services/s3/src/main/java/dev/cloudlite/s3/controller/BucketController.java \
  services/s3/src/test/java/dev/cloudlite/s3/service/BucketServiceTest.java \
  services/s3/src/test/java/dev/cloudlite/s3/controller/BucketControllerTest.java
git commit -m "feat: add bucket service and HTTP handlers"
```

---

## Task 6: Object service + controller

**Files:**
- Create: `services/s3/src/main/java/dev/cloudlite/s3/service/ObjectService.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/controller/ObjectController.java`
- Test: `services/s3/src/test/java/dev/cloudlite/s3/service/ObjectServiceTest.java`
- Test: `services/s3/src/test/java/dev/cloudlite/s3/controller/ObjectControllerTest.java`

**Interfaces:**
- Consumes: `BucketRepository`, `ObjectRepository`, `ObjectMetadata`, `ObjectMetadataId` (Task 3); `BlobStore` (Task 2); `S3ApiException`, `S3ErrorCode`, `GlobalExceptionHandler` (Task 4).
- Produces: `ObjectService(BucketRepository buckets, ObjectRepository objects, BlobStore store)` with `long maxObjectSize()`, `String put(String bucket, String key, byte[] body, String contentType)`, `ObjectMetadata get(String bucket, String key)`, `InputStream getBlob(ObjectMetadata metadata)`, `Optional<ObjectMetadata> find(String bucket, String key)`, `void delete(String bucket, String key)`. `ObjectController` mapped to `PUT/GET/HEAD/DELETE /{bucket}/{*key}`.

- [ ] **Step 1: Write the failing `ObjectService` unit test**

`services/s3/src/test/java/dev/cloudlite/s3/service/ObjectServiceTest.java`:

```java
package dev.cloudlite.s3.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.domain.ObjectMetadataId;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.repository.BucketRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import dev.cloudlite.s3.storage.BlobStore;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class ObjectServiceTest {

    private BucketRepository buckets;
    private ObjectRepository objects;
    private BlobStore store;
    private ObjectService service;

    @BeforeEach
    void setUp() {
        buckets = mock(BucketRepository.class);
        objects = mock(ObjectRepository.class);
        store = mock(BlobStore.class);
        service = new ObjectService(buckets, objects, store);
    }

    @Test
    void putRejectsWhenTheBucketDoesNotExist() {
        when(buckets.existsById("photos")).thenReturn(false);

        assertThatThrownBy(() -> service.put("photos", "cat.png", "hi".getBytes(), "text/plain"))
            .isInstanceOf(S3ApiException.class)
            .extracting(e -> ((S3ApiException) e).getErrorCode())
            .isEqualTo(S3ErrorCode.NO_SUCH_BUCKET);
    }

    @Test
    void putDefaultsContentTypeWhenAbsent() {
        when(buckets.existsById("photos")).thenReturn(true);
        when(objects.findById(any())).thenReturn(Optional.empty());

        service.put("photos", "cat.png", "hi".getBytes(), null);

        ArgumentCaptor<ObjectMetadata> captor = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(objects).save(captor.capture());
        assertThat(captor.getValue().getContentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void putDeletesTheSupersededBlobAfterAnOverwrite() {
        UUID oldStorageId = UUID.randomUUID();
        when(buckets.existsById("photos")).thenReturn(true);
        when(objects.findById(new ObjectMetadataId("photos", "cat.png")))
            .thenReturn(Optional.of(new ObjectMetadata("photos", "cat.png", "image/png", 1L, "old-etag", oldStorageId)));

        service.put("photos", "cat.png", "hi".getBytes(), "image/png");

        verify(store).delete(oldStorageId);
    }

    @Test
    void putDoesNotAttemptToDeleteAnyBlobOnANewKey() {
        when(buckets.existsById("photos")).thenReturn(true);
        when(objects.findById(any())).thenReturn(Optional.empty());

        service.put("photos", "cat.png", "hi".getBytes(), "image/png");

        verify(store, never()).delete(any());
    }

    @Test
    void getThrowsNoSuchKeyWhenTheObjectIsMissing() {
        when(objects.findById(new ObjectMetadataId("photos", "missing.png"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("photos", "missing.png"))
            .isInstanceOf(S3ApiException.class)
            .extracting(e -> ((S3ApiException) e).getErrorCode())
            .isEqualTo(S3ErrorCode.NO_SUCH_KEY);
    }

    @Test
    void deleteIsANoOpWhenTheKeyNeverExisted() {
        when(objects.findById(new ObjectMetadataId("photos", "missing.png"))).thenReturn(Optional.empty());

        service.delete("photos", "missing.png");

        verify(objects, never()).deleteById(any());
        verify(store, never()).delete(any());
    }

    @Test
    void deleteRemovesMetadataBeforeTheBlob() {
        UUID storageId = UUID.randomUUID();
        ObjectMetadataId id = new ObjectMetadataId("photos", "cat.png");
        when(objects.findById(id))
            .thenReturn(Optional.of(new ObjectMetadata("photos", "cat.png", "image/png", 1L, "etag", storageId)));

        service.delete("photos", "cat.png");

        InOrder order = inOrder(objects, store);
        order.verify(objects).deleteById(id);
        order.verify(store).delete(storageId);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd services/s3 && mvn -q test -Dtest=ObjectServiceTest`
Expected: FAIL — `ObjectService` does not exist.

- [ ] **Step 3: Implement `ObjectService`**

```java
package dev.cloudlite.s3.service;

import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.domain.ObjectMetadataId;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.repository.BucketRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import dev.cloudlite.s3.storage.BlobStore;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ObjectService {

    private static final Logger log = LoggerFactory.getLogger(ObjectService.class);
    private static final long MAX_OBJECT_SIZE = 100L * 1024 * 1024; // 100 MiB

    private final BucketRepository buckets;
    private final ObjectRepository objects;
    private final BlobStore store;

    public ObjectService(BucketRepository buckets, ObjectRepository objects, BlobStore store) {
        this.buckets = buckets;
        this.objects = objects;
        this.store = store;
    }

    public long maxObjectSize() {
        return MAX_OBJECT_SIZE;
    }

    public String put(String bucket, String key, byte[] body, String contentType) {
        if (!buckets.existsById(bucket)) {
            throw new S3ApiException(S3ErrorCode.NO_SUCH_BUCKET, bucket);
        }

        Optional<ObjectMetadata> existing = objects.findById(new ObjectMetadataId(bucket, key));

        String etag = md5Hex(body);
        UUID storageId = UUID.randomUUID();
        store.put(storageId, new ByteArrayInputStream(body));

        String resolvedContentType = (contentType == null || contentType.isBlank())
            ? "application/octet-stream"
            : contentType;

        try {
            objects.save(new ObjectMetadata(bucket, key, resolvedContentType, body.length, etag, storageId));
        } catch (RuntimeException e) {
            log.error("s3: put object {}/{}: blob {} written but metadata upsert failed, blob is orphaned",
                bucket, key, storageId, e);
            throw e;
        }

        existing.ifPresent(old -> {
            try {
                store.delete(old.getStorageId());
            } catch (RuntimeException e) {
                log.warn("s3: put object {}/{}: failed to delete superseded blob {}", bucket, key, old.getStorageId(), e);
            }
        });

        return etag;
    }

    public ObjectMetadata get(String bucket, String key) {
        return objects.findById(new ObjectMetadataId(bucket, key))
            .orElseThrow(() -> new S3ApiException(S3ErrorCode.NO_SUCH_KEY, key));
    }

    public InputStream getBlob(ObjectMetadata metadata) {
        return store.get(metadata.getStorageId());
    }

    public Optional<ObjectMetadata> find(String bucket, String key) {
        return objects.findById(new ObjectMetadataId(bucket, key));
    }

    public void delete(String bucket, String key) {
        ObjectMetadataId id = new ObjectMetadataId(bucket, key);
        Optional<ObjectMetadata> existing = objects.findById(id);
        if (existing.isEmpty()) {
            return;
        }
        objects.deleteById(id);
        try {
            store.delete(existing.get().getStorageId());
        } catch (RuntimeException e) {
            log.warn("s3: delete object {}/{}: blob {} delete failed after metadata delete, blob is orphaned",
                bucket, key, existing.get().getStorageId(), e);
        }
    }

    private static String md5Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd services/s3 && mvn -q test -Dtest=ObjectServiceTest`
Expected: PASS

- [ ] **Step 5: Write the failing `ObjectController` MockMvc test**

`services/s3/src/test/java/dev/cloudlite/s3/controller/ObjectControllerTest.java`:

```java
package dev.cloudlite.s3.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.error.GlobalExceptionHandler;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.service.ObjectService;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ObjectController.class)
@Import(GlobalExceptionHandler.class)
class ObjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ObjectService objectService;

    @Test
    void putReturns200WithAnEtagHeader() throws Exception {
        given(objectService.maxObjectSize()).willReturn(100L * 1024 * 1024);
        given(objectService.put(eq("photos"), eq("cat.png"), any(), eq("image/png"))).willReturn("abc123");

        mockMvc.perform(put("/photos/cat.png").contentType(MediaType.IMAGE_PNG).content("hi".getBytes()))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"abc123\""));
    }

    @Test
    void putReturns404WhenTheBucketIsMissing() throws Exception {
        given(objectService.maxObjectSize()).willReturn(100L * 1024 * 1024);
        doThrow(new S3ApiException(S3ErrorCode.NO_SUCH_BUCKET, "photos"))
            .when(objectService).put(eq("photos"), eq("cat.png"), any(), any());

        mockMvc.perform(put("/photos/cat.png").content("hi".getBytes()))
            .andExpect(status().isNotFound());
    }

    @Test
    void putReturns400WhenTheBodyExceedsTheMaxObjectSize() throws Exception {
        given(objectService.maxObjectSize()).willReturn(4L);

        mockMvc.perform(put("/photos/cat.png").content("hello world".getBytes()))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("EntityTooLarge")));
    }

    @Test
    void getReturns200WithHeadersAndBody() throws Exception {
        ObjectMetadata metadata = new ObjectMetadata("photos", "cat.png", "image/png", 2L, "abc123", UUID.randomUUID());
        given(objectService.get("photos", "cat.png")).willReturn(metadata);
        given(objectService.getBlob(metadata)).willReturn(new ByteArrayInputStream("hi".getBytes()));

        mockMvc.perform(get("/photos/cat.png"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(header().string("ETag", "\"abc123\""));
    }

    @Test
    void getReturns404WhenTheKeyIsMissing() throws Exception {
        given(objectService.get("photos", "missing.png"))
            .willThrow(new S3ApiException(S3ErrorCode.NO_SUCH_KEY, "missing.png"));

        mockMvc.perform(get("/photos/missing.png")).andExpect(status().isNotFound());
    }

    @Test
    void headReturns404WithNoBodyWhenTheKeyIsMissing() throws Exception {
        given(objectService.find("photos", "missing.png")).willReturn(Optional.empty());

        mockMvc.perform(head("/photos/missing.png"))
            .andExpect(status().isNotFound())
            .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void deleteAlwaysReturns204() throws Exception {
        mockMvc.perform(delete("/photos/cat.png")).andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `cd services/s3 && mvn -q test -Dtest=ObjectControllerTest`
Expected: FAIL — `ObjectController` does not exist.

- [ ] **Step 7: Implement `ObjectController`**

```java
package dev.cloudlite.s3.controller;

import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.service.ObjectService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ObjectController {

    private static final DateTimeFormatter LAST_MODIFIED_FORMAT =
        DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final ObjectService objectService;

    public ObjectController(ObjectService objectService) {
        this.objectService = objectService;
    }

    @PutMapping("/{bucket}/{*key}")
    public ResponseEntity<Void> put(
            @PathVariable String bucket,
            @PathVariable String key,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            HttpServletRequest request) throws IOException {
        byte[] body = readBoundedBody(request.getInputStream(), objectService.maxObjectSize());
        String etag = objectService.put(bucket, stripLeadingSlash(key), body, contentType);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, "\"" + etag + "\"").build();
    }

    @GetMapping("/{bucket}/{*key}")
    public ResponseEntity<InputStreamResource> get(@PathVariable String bucket, @PathVariable String key) {
        ObjectMetadata metadata = objectService.get(bucket, stripLeadingSlash(key));
        InputStream blob = objectService.getBlob(metadata);
        return ResponseEntity.ok().headers(headersFor(metadata)).body(new InputStreamResource(blob));
    }

    @RequestMapping(path = "/{bucket}/{*key}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(@PathVariable String bucket, @PathVariable String key) {
        return objectService.find(bucket, stripLeadingSlash(key))
            .map(metadata -> ResponseEntity.ok().headers(headersFor(metadata)).<Void>build())
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{bucket}/{*key}")
    public ResponseEntity<Void> delete(@PathVariable String bucket, @PathVariable String key) {
        objectService.delete(bucket, stripLeadingSlash(key));
        return ResponseEntity.noContent().build();
    }

    private HttpHeaders headersFor(ObjectMetadata metadata) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, metadata.getContentType());
        headers.set(HttpHeaders.CONTENT_LENGTH, Long.toString(metadata.getSizeBytes()));
        headers.set(HttpHeaders.ETAG, "\"" + metadata.getEtag() + "\"");
        headers.set(HttpHeaders.LAST_MODIFIED, LAST_MODIFIED_FORMAT.format(metadata.getCreatedAt()));
        return headers;
    }

    private static String stripLeadingSlash(String key) {
        return key.startsWith("/") ? key.substring(1) : key;
    }

    private static byte[] readBoundedBody(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new S3ApiException(S3ErrorCode.ENTITY_TOO_LARGE, "");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd services/s3 && mvn -q test -Dtest=ObjectServiceTest,ObjectControllerTest`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add services/s3/src/main/java/dev/cloudlite/s3/service/ObjectService.java \
  services/s3/src/main/java/dev/cloudlite/s3/controller/ObjectController.java \
  services/s3/src/test/java/dev/cloudlite/s3/service/ObjectServiceTest.java \
  services/s3/src/test/java/dev/cloudlite/s3/controller/ObjectControllerTest.java
git commit -m "feat: add object service and HTTP handlers"
```

---

## Task 7: `docker-compose.yml` + `Dockerfile` for local dev

**Files:**
- Create: `services/s3/Dockerfile`
- Create: `docker-compose.yml`

**Interfaces:**
- Consumes: nothing from earlier tasks directly, but assumes `pom.xml`'s `artifactId=s3`/`version=0.1.0` (Task 1), producing `target/s3-0.1.0.jar`.

- [ ] **Step 1: Create the Dockerfile**

`services/s3/Dockerfile`:

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /src/target/s3-0.1.0.jar /app/s3.jar
ENTRYPOINT ["java", "-jar", "/app/s3.jar"]
```

- [ ] **Step 2: Create `docker-compose.yml`**

`docker-compose.yml` (repo root):

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
      SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres:5432/cloudlite"
      SPRING_DATASOURCE_USERNAME: "cloudlite"
      SPRING_DATASOURCE_PASSWORD: "cloudlite"
      S3_DATA_DIR: "/data"
      SERVER_PORT: "8080"
      JAVA_TOOL_OPTIONS: "-Xmx768m"
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

- [ ] **Step 3: Verify the stack starts and serves real requests**

Run: `docker compose up --build -d`
Expected: both containers report healthy/running; `docker compose logs s3` shows the Spring Boot startup banner with no errors (allow extra time for JVM warm-up + Maven build vs. the near-instant Go binary).

Run:
```bash
curl -i http://localhost:8080/healthz
curl -i -X PUT http://localhost:8080/mybucket
curl -i -X PUT http://localhost:8080/mybucket/hello.txt -H "Content-Type: text/plain" --data "hello world"
curl -i http://localhost:8080/mybucket/hello.txt
curl -i http://localhost:8080/
```
Expected: `200` on `/healthz`, `200` on bucket create, `200` with an `ETag` header on the object PUT, `200` with `hello world` as the body on the object GET, and `200` with an XML `ListAllMyBucketsResult` body containing `mybucket` on the final call.

Run: `docker compose down`

- [ ] **Step 4: Commit**

```bash
git add services/s3/Dockerfile docker-compose.yml
git commit -m "build: add docker-compose local dev loop for the Java s3 service"
```

---

## Task 8: End-to-end integration test + `docs/services/s3.md` update

**Files:**
- Test: `services/s3/src/test/java/dev/cloudlite/s3/S3ApplicationIntegrationTest.java`
- Modify: `docs/services/s3.md`

**Interfaces:**
- Consumes: the full stack from Tasks 1–6 (real Spring context, real Postgres via Testcontainers, real `DiskBlobStore` against a temp directory).

- [ ] **Step 1: Write the end-to-end integration test**

`services/s3/src/test/java/dev/cloudlite/s3/S3ApplicationIntegrationTest.java`:

```java
package dev.cloudlite.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class S3ApplicationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDirProperty(DynamicPropertyRegistry registry) {
        registry.add("s3.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthzReturns200OnceTheAppIsUp() {
        ResponseEntity<Void> response = restTemplate.getForEntity("/healthz", Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void createBucketThenPutGetAndDeleteAnObject() {
        restTemplate.put("/e2e-bucket", null);

        ResponseEntity<Void> head = restTemplate.exchange("/e2e-bucket", HttpMethod.HEAD, null, Void.class);
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders putHeaders = new HttpHeaders();
        putHeaders.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<Void> put = restTemplate.exchange(
            "/e2e-bucket/hello.txt", HttpMethod.PUT, new HttpEntity<>("hello world".getBytes(), putHeaders), Void.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getHeaders().getETag()).isNotBlank();

        ResponseEntity<byte[]> get = restTemplate.getForEntity("/e2e-bucket/hello.txt", byte[].class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(get.getBody())).isEqualTo("hello world");
        assertThat(get.getHeaders().getETag()).isEqualTo(put.getHeaders().getETag());

        restTemplate.delete("/e2e-bucket/hello.txt");

        ResponseEntity<byte[]> getAfterDelete = restTemplate.getForEntity("/e2e-bucket/hello.txt", byte[].class);
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        restTemplate.delete("/e2e-bucket");
    }
}
```

- [ ] **Step 2: Run the full test suite**

Run: `cd services/s3 && mvn test`
Expected: PASS — every test class from Tasks 1–8, including this new end-to-end test (requires Docker for Testcontainers).

- [ ] **Step 3: Update `docs/services/s3.md`**

Replace the file's contents with:

```markdown
# S3 clone

**Status:** Phase 1 (foundation) built — bucket CRUD and basic object
PUT/GET/DELETE/HEAD, wire-compatible with AWS S3's REST API (path-style,
no ranges/versioning/multipart yet). Java/Spring Boot implementation,
per the 2026-08-19 language pivot in `architecture.md` §3. See
[`../superpowers/plans/2026-08-19-s3-clone-phase1-java.md`](../superpowers/plans/2026-08-19-s3-clone-phase1-java.md)
for what was built and
[`../superpowers/specs/2026-08-19-s3-clone-phase1-java-design.md`](../superpowers/specs/2026-08-19-s3-clone-phase1-java-design.md)
for the design. Phases 2-4 (byte-range GET + tags, versioning, multipart)
are not yet built.

## Scope

- Buckets: create/list/delete, per-bucket policy attachment
- Objects: PUT/GET/DELETE/HEAD, byte-range GET
- Multipart upload: initiate → upload parts → complete/abort, **with
  crash recovery** (kill mid-upload, verify clean retry or orphan
  cleanup — the best interview anecdote in the project)
- Versioning: keep prior versions, list, restore
- Metadata: content-type, custom tags

## Tech stack

- Java 21, Spring Boot (Spring MVC + virtual threads), Maven
- Spring Data JPA + Hibernate over PostgreSQL, Flyway migrations
- `jackson-dataformat-xml` for AWS-shaped XML request/response bodies

## Storage

- Object bytes: local disk, content/UUID-addressed, on the `bulk-hdd`
  storage class
- Object/bucket index: PostgreSQL (see
  [`../decisions/0005-postgresql-database.md`](../decisions/0005-postgresql-database.md)),
  on `fast-ssd`

## Dependencies

- Calls out to the IAM service on every request via an `iamclient`
  package for policy evaluation (see [`iam.md`](iam.md)) — wired in
  after both services exist standalone (`architecture.md` §11, step 3).

## Build/test notes

Per `architecture.md` §11: build and test standalone first (no auth),
via `docker-compose` + curl/test scripts, before IAM is wired in.
Expose `/healthz` from the first commit (`architecture.md` §10).

## Out of scope

See `../future-work.md` — cross-region replication, lifecycle
policies, server-side encryption, and event notifications beyond the
single object-created → function-runner trigger are explicitly not
part of this service.
```

- [ ] **Step 4: Commit**

```bash
git add services/s3/src/test/java/dev/cloudlite/s3/S3ApplicationIntegrationTest.java docs/services/s3.md
git commit -m "test: add end-to-end integration test, update s3 service doc"
```
