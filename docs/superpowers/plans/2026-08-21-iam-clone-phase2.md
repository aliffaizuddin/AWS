# IAM Clone Phase 2 (Standalone) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up `services/iam` as a working, standalone Spring Boot (Java 21) service: user/role/policy CRUD, a deny-overrides-allow policy evaluation engine, and an API-key-to-JWT auth flow with a JWT-gated `/authorize` decision endpoint — the exact surface S3 will call in a later build-order step.

**Architecture:** A Spring Boot application layered as `controller/` (thin HTTP binding) → `service/` (business logic, auth, policy resolution) → `repository/` (Spring Data JPA over Postgres), with a framework-free `policy/` package holding the pure evaluation engine, and a shared `error/` package rendering JSON error bodies via a `@RestControllerAdvice`.

**Tech Stack:** Java 21, Spring Boot 3.3.4 (Spring MVC + virtual threads), Maven, Spring Data JPA + Hibernate, Flyway, jjwt 0.12.x, JUnit 5 + Mockito + AssertJ, Testcontainers (`@ServiceConnection`).

**Spec:** [`docs/superpowers/specs/2026-08-21-iam-clone-phase2-design.md`](../specs/2026-08-21-iam-clone-phase2-design.md)

## Global Constraints

- **Branch note (read before Task 1):** this plan document currently lives on branch `docs/iam-clone-phase2-design`, which so far holds only the spec commit. Before starting Task 1, confirm with the human partner whether to continue implementing on this branch or cut a fresh `feat/iam-clone-phase2` branch off `main` first — the prior S3 rewrite used separate `docs/*` branches for its spec/plan and a dedicated `feat/*` branch for implementation; don't assume either way here without asking.
- Module root: `services/iam` — a fresh Maven project; nothing under this path exists yet on any branch.
- Build tool: Maven, no wrapper. `groupId=dev.cloudlite`, `artifactId=iam`, `version=0.1.0`, package root `dev.cloudlite.iam`.
- Spring Boot 3.3.4 parent, Java 21. Spring MVC (not WebFlux). `spring.threads.virtual.enabled=true`.
- Persistence via Spring Data JPA + Hibernate; schema managed by Flyway migrations, never `hibernate.ddl-auto=update`.
- Plain JSON REST — no AWS wire-compatibility requirement (unlike S3; nothing consumes IAM via an AWS SDK/CLI).
- Entity primary keys (`User`, `Role`, `Policy`) are `UUID` fields assigned by the constructor via `UUID.randomUUID()` — **not** `@GeneratedValue`. This keeps every entity usable (with a real, non-null id) the instant it's constructed, in plain unit tests, with no persistence round-trip required.
- Admin CRUD endpoints (users/roles/policies, attachments) are open — no auth — in this phase, mirroring S3 Phase 1's own bootstrap posture. Only `/auth/token` (gated by API key) and `/authorize` (gated by a JWT) are auth-gated.
- JWT via jjwt 0.12.x (`jjwt-api`/`jjwt-impl`/`jjwt-jackson`), HMAC-SHA256. Signing secret from `iam.jwt.secret` (must be ≥32 bytes UTF-8, enforced at startup by throwing `IllegalStateException` otherwise); default expiry 900 seconds via `iam.jwt.expiry-seconds`.
- API keys: 32 random bytes (`SecureRandom`), Base64 URL-encoded without padding, as the raw key handed to the caller exactly once (at user creation or never again); only its SHA-256 hex digest is ever persisted.
- `Policy.document` is stored as a raw JSON string in a `jsonb` column via Hibernate's native `@JdbcTypeCode(SqlTypes.JSON)` (`org.hibernate.annotations.JdbcTypeCode` / `org.hibernate.type.SqlTypes`) — no extra Hibernate-types dependency needed.
- Every Jackson-bound type (request/response DTOs in `dto/`, and `policy.PolicyStatement`/`policy.PolicyDocument`) is a plain Java `record` — Jackson's native record support (via `RecordComponent` reflection, no extra module or compiler flag required) serializes and deserializes them with component names as the JSON field names.
- Roles are policy bundles only: a `User` can be a member of zero or more `Role`s (static membership, no assume-role/session semantics) and can also have policies attached directly. Cross-account roles, MFA/SSO/federation, and fine-grained condition keys are permanently out of scope per `docs/future-work.md` — not just deferred for this phase.
- Resource identifiers are simplified ARNs: `arn:cloudlite:s3:::<bucket>` / `arn:cloudlite:s3:::<bucket>/<key>`. Action/resource matching in the policy engine supports an exact string match or a pattern ending in a single trailing `*` (prefix match).
- `IamErrorCode` is exactly the set defined in Task 3 below — mirrors the spec's §7 exactly. Do not add new codes. In particular, the spec never defines a `POLICY_ALREADY_EXISTS` code even though `policies.name` is `UNIQUE`; `PolicyService.create` reuses `INVALID_ARGUMENT` (400) for a duplicate policy name rather than inventing one.
- No wiring into S3 in this phase — that's a separate, later build-order step and a separate future spec/plan.
- Every task commits with a Conventional Commit message (`feat|test|build|docs`) per `docs/decisions/0012-commit-and-branch-conventions.md`.

---

## Task 1: Scaffold the Maven/Spring Boot project + `/healthz`

**Files:**
- Create: `services/iam/pom.xml`
- Create: `services/iam/.gitignore`
- Create: `services/iam/src/main/resources/application.yml`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/IamApplication.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/controller/HealthController.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/controller/HealthControllerTest.java`

**Interfaces:**
- Produces: `HealthController` mapped to `GET /healthz`, constructor `HealthController(DataSource dataSource)`. Later tasks don't depend on this class directly.
- Consumes: nothing from earlier tasks.

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
  <artifactId>iam</artifactId>
  <version>0.1.0</version>
  <name>iam</name>
  <description>CloudLite IAM clone service</description>

  <properties>
    <java.version>21</java.version>
    <jjwt.version>0.12.6</jjwt.version>
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
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-api</artifactId>
      <version>${jjwt.version}</version>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-impl</artifactId>
      <version>${jjwt.version}</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-jackson</artifactId>
      <version>${jjwt.version}</version>
      <scope>runtime</scope>
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
        <version>1.21.4</version>
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

Note: `testcontainers-bom` is pinned to `1.21.4` (not the older `1.20.2`) from the start — the S3 rewrite discovered mid-implementation that `1.20.2`'s bundled `docker-java` default speaks an old Docker API version that a modern Docker daemon rejects (`client version 1.32 is too old`). Starting on `1.21.4` avoids rediscovering that here.

- [ ] **Step 2: Create `.gitignore`**

```
target/
```

- [ ] **Step 3: Create `application.yml`**

```yaml
server:
  port: ${SERVER_PORT:8081}

spring:
  application:
    name: iam
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

iam:
  jwt:
    secret: ${IAM_JWT_SECRET:dev-only-insecure-jwt-signing-secret-please-change}
    expiry-seconds: ${IAM_JWT_EXPIRY_SECONDS:900}
```

- [ ] **Step 4: Create the main application class**

```java
package dev.cloudlite.iam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IamApplication {

    public static void main(String[] args) {
        SpringApplication.run(IamApplication.class, args);
    }
}
```

- [ ] **Step 5: Write the failing test for `/healthz`**

`services/iam/src/test/java/dev/cloudlite/iam/controller/HealthControllerTest.java`:

```java
package dev.cloudlite.iam.controller;

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

- [ ] **Step 6: Run the test to verify it fails**

Run: `cd services/iam && mvn -q test -Dtest=HealthControllerTest`
Expected: FAIL — `HealthController` does not exist (compile error).

- [ ] **Step 7: Implement `HealthController`**

```java
package dev.cloudlite.iam.controller;

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

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd services/iam && mvn -q test -Dtest=HealthControllerTest`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add services/iam/pom.xml services/iam/.gitignore services/iam/src/main/resources/application.yml \
  services/iam/src/main/java/dev/cloudlite/iam/IamApplication.java \
  services/iam/src/main/java/dev/cloudlite/iam/controller/HealthController.java \
  services/iam/src/test/java/dev/cloudlite/iam/controller/HealthControllerTest.java
git commit -m "feat: scaffold iam Spring Boot project with /healthz"
```

---

## Task 2: Postgres schema (Flyway) + JPA entities + repositories

**Files:**
- Create: `services/iam/src/main/resources/db/migration/V1__create_users.sql`
- Create: `services/iam/src/main/resources/db/migration/V2__create_roles.sql`
- Create: `services/iam/src/main/resources/db/migration/V3__create_policies.sql`
- Create: `services/iam/src/main/resources/db/migration/V4__create_attachments.sql`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/domain/User.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/domain/Role.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/domain/Policy.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/domain/UserRoleId.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/domain/UserRole.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/domain/UserPolicyId.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/domain/UserPolicy.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/domain/RolePolicyId.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/domain/RolePolicy.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/repository/UserRepository.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/repository/RoleRepository.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/repository/PolicyRepository.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/repository/UserRoleRepository.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/repository/UserPolicyRepository.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/repository/RolePolicyRepository.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/repository/UserRepositoryTest.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/repository/RoleRepositoryTest.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/repository/PolicyRepositoryTest.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/repository/AttachmentRepositoryTest.java`

**Interfaces:**
- Produces: `User(String username, String apiKeyHash)` with `getId()`/`getUsername()`/`getApiKeyHash()`/`getCreatedAt()` (id is a `UUID` assigned in the constructor, not DB-generated). `Role(String name)` with `getId()`/`getName()`/`getCreatedAt()`. `Policy(String name, String document)` with `getId()`/`getName()`/`getDocument()`/`getCreatedAt()` (`document` is a raw JSON string). `UserRole(UUID userId, UUID roleId)` with `getId()`/`getUserId()`/`getRoleId()`. `UserPolicy(UUID userId, UUID policyId)` with `getId()`/`getUserId()`/`getPolicyId()`. `RolePolicy(UUID roleId, UUID policyId)` with `getId()`/`getRoleId()`/`getPolicyId()`. `UserRepository extends JpaRepository<User, UUID>` with `boolean existsByUsername(String)` and `Optional<User> findByApiKeyHash(String)`. `RoleRepository extends JpaRepository<Role, UUID>` with `boolean existsByName(String)`. `PolicyRepository extends JpaRepository<Policy, UUID>` with `boolean existsByName(String)`. `UserRoleRepository extends JpaRepository<UserRole, UserRoleId>` with `List<UserRole> findByIdUserId(UUID)`. `UserPolicyRepository extends JpaRepository<UserPolicy, UserPolicyId>` with `List<UserPolicy> findByIdUserId(UUID)`. `RolePolicyRepository extends JpaRepository<RolePolicy, RolePolicyId>` with `List<RolePolicy> findByIdRoleId(UUID)`.
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Create the Flyway migrations**

`services/iam/src/main/resources/db/migration/V1__create_users.sql`:

```sql
CREATE TABLE users (
    id           UUID PRIMARY KEY,
    username     TEXT NOT NULL UNIQUE,
    api_key_hash TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`services/iam/src/main/resources/db/migration/V2__create_roles.sql`:

```sql
CREATE TABLE roles (
    id         UUID PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`services/iam/src/main/resources/db/migration/V3__create_policies.sql`:

```sql
CREATE TABLE policies (
    id         UUID PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    document   JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`services/iam/src/main/resources/db/migration/V4__create_attachments.sql`:

```sql
CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id),
    role_id UUID NOT NULL REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE user_policies (
    user_id   UUID NOT NULL REFERENCES users(id),
    policy_id UUID NOT NULL REFERENCES policies(id),
    PRIMARY KEY (user_id, policy_id)
);

CREATE TABLE role_policies (
    role_id   UUID NOT NULL REFERENCES roles(id),
    policy_id UUID NOT NULL REFERENCES policies(id),
    PRIMARY KEY (role_id, policy_id)
);
```

Note: primary keys are plain `UUID` with no `DEFAULT gen_random_uuid()` — the JPA entities (Step 2) assign the id themselves before insert, so the column never relies on a DB-side default.

- [ ] **Step 2: Create the `User`, `Role`, and `Policy` entities**

`services/iam/src/main/java/dev/cloudlite/iam/domain/User.java`:

```java
package dev.cloudlite.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "api_key_hash", nullable = false)
    private String apiKeyHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected User() {
        // for JPA
    }

    public User(String username, String apiKeyHash) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.apiKeyHash = apiKeyHash;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
```

`services/iam/src/main/java/dev/cloudlite/iam/domain/Role.java`:

```java
package dev.cloudlite.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "roles")
public class Role {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Role() {
        // for JPA
    }

    public Role(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
```

`services/iam/src/main/java/dev/cloudlite/iam/domain/Policy.java`:

```java
package dev.cloudlite.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "policies")
public class Policy {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String document;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Policy() {
        // for JPA
    }

    public Policy(String name, String document) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.document = document;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDocument() {
        return document;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
```

- [ ] **Step 3: Create the join entities**

`services/iam/src/main/java/dev/cloudlite/iam/domain/UserRoleId.java`:

```java
package dev.cloudlite.iam.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class UserRoleId implements Serializable {

    private UUID userId;
    private UUID roleId;

    protected UserRoleId() {
        // for JPA
    }

    public UserRoleId(UUID userId, UUID roleId) {
        this.userId = userId;
        this.roleId = roleId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserRoleId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(roleId, that.roleId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, roleId);
    }
}
```

`services/iam/src/main/java/dev/cloudlite/iam/domain/UserRole.java`:

```java
package dev.cloudlite.iam.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "user_roles")
public class UserRole {

    @EmbeddedId
    @AttributeOverride(name = "userId", column = @Column(name = "user_id"))
    @AttributeOverride(name = "roleId", column = @Column(name = "role_id"))
    private UserRoleId id;

    protected UserRole() {
        // for JPA
    }

    public UserRole(UUID userId, UUID roleId) {
        this.id = new UserRoleId(userId, roleId);
    }

    public UserRoleId getId() {
        return id;
    }

    public UUID getUserId() {
        return id.getUserId();
    }

    public UUID getRoleId() {
        return id.getRoleId();
    }
}
```

`services/iam/src/main/java/dev/cloudlite/iam/domain/UserPolicyId.java`:

```java
package dev.cloudlite.iam.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class UserPolicyId implements Serializable {

    private UUID userId;
    private UUID policyId;

    protected UserPolicyId() {
        // for JPA
    }

    public UserPolicyId(UUID userId, UUID policyId) {
        this.userId = userId;
        this.policyId = policyId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserPolicyId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(policyId, that.policyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, policyId);
    }
}
```

`services/iam/src/main/java/dev/cloudlite/iam/domain/UserPolicy.java`:

```java
package dev.cloudlite.iam.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "user_policies")
public class UserPolicy {

    @EmbeddedId
    @AttributeOverride(name = "userId", column = @Column(name = "user_id"))
    @AttributeOverride(name = "policyId", column = @Column(name = "policy_id"))
    private UserPolicyId id;

    protected UserPolicy() {
        // for JPA
    }

    public UserPolicy(UUID userId, UUID policyId) {
        this.id = new UserPolicyId(userId, policyId);
    }

    public UserPolicyId getId() {
        return id;
    }

    public UUID getUserId() {
        return id.getUserId();
    }

    public UUID getPolicyId() {
        return id.getPolicyId();
    }
}
```

`services/iam/src/main/java/dev/cloudlite/iam/domain/RolePolicyId.java`:

```java
package dev.cloudlite.iam.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class RolePolicyId implements Serializable {

    private UUID roleId;
    private UUID policyId;

    protected RolePolicyId() {
        // for JPA
    }

    public RolePolicyId(UUID roleId, UUID policyId) {
        this.roleId = roleId;
        this.policyId = policyId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RolePolicyId that)) {
            return false;
        }
        return Objects.equals(roleId, that.roleId) && Objects.equals(policyId, that.policyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleId, policyId);
    }
}
```

`services/iam/src/main/java/dev/cloudlite/iam/domain/RolePolicy.java`:

```java
package dev.cloudlite.iam.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "role_policies")
public class RolePolicy {

    @EmbeddedId
    @AttributeOverride(name = "roleId", column = @Column(name = "role_id"))
    @AttributeOverride(name = "policyId", column = @Column(name = "policy_id"))
    private RolePolicyId id;

    protected RolePolicy() {
        // for JPA
    }

    public RolePolicy(UUID roleId, UUID policyId) {
        this.id = new RolePolicyId(roleId, policyId);
    }

    public RolePolicyId getId() {
        return id;
    }

    public UUID getRoleId() {
        return id.getRoleId();
    }

    public UUID getPolicyId() {
        return id.getPolicyId();
    }
}
```

- [ ] **Step 4: Create the repositories**

`services/iam/src/main/java/dev/cloudlite/iam/repository/UserRepository.java`:

```java
package dev.cloudlite.iam.repository;

import dev.cloudlite.iam.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByUsername(String username);

    Optional<User> findByApiKeyHash(String apiKeyHash);
}
```

`services/iam/src/main/java/dev/cloudlite/iam/repository/RoleRepository.java`:

```java
package dev.cloudlite.iam.repository;

import dev.cloudlite.iam.domain.Role;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    boolean existsByName(String name);
}
```

`services/iam/src/main/java/dev/cloudlite/iam/repository/PolicyRepository.java`:

```java
package dev.cloudlite.iam.repository;

import dev.cloudlite.iam.domain.Policy;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {

    boolean existsByName(String name);
}
```

`services/iam/src/main/java/dev/cloudlite/iam/repository/UserRoleRepository.java`:

```java
package dev.cloudlite.iam.repository;

import dev.cloudlite.iam.domain.UserRole;
import dev.cloudlite.iam.domain.UserRoleId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    List<UserRole> findByIdUserId(UUID userId);
}
```

`services/iam/src/main/java/dev/cloudlite/iam/repository/UserPolicyRepository.java`:

```java
package dev.cloudlite.iam.repository;

import dev.cloudlite.iam.domain.UserPolicy;
import dev.cloudlite.iam.domain.UserPolicyId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPolicyRepository extends JpaRepository<UserPolicy, UserPolicyId> {

    List<UserPolicy> findByIdUserId(UUID userId);
}
```

`services/iam/src/main/java/dev/cloudlite/iam/repository/RolePolicyRepository.java`:

```java
package dev.cloudlite.iam.repository;

import dev.cloudlite.iam.domain.RolePolicy;
import dev.cloudlite.iam.domain.RolePolicyId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePolicyRepository extends JpaRepository<RolePolicy, RolePolicyId> {

    List<RolePolicy> findByIdRoleId(UUID roleId);
}
```

- [ ] **Step 5: Write the repository tests (Testcontainers, real Postgres)**

`services/iam/src/test/java/dev/cloudlite/iam/repository/UserRepositoryTest.java`:

```java
package dev.cloudlite.iam.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cloudlite.iam.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UserRepository userRepository;

    @Test
    void savedUserCanBeFoundById() {
        User saved = userRepository.save(new User("alice", "hash123"));

        assertThat(userRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void existsByUsernameIsTrueOnceCreated() {
        userRepository.save(new User("bob", "hash456"));

        assertThat(userRepository.existsByUsername("bob")).isTrue();
    }

    @Test
    void existsByUsernameIsFalseForAnUnknownUsername() {
        assertThat(userRepository.existsByUsername("nobody")).isFalse();
    }

    @Test
    void findByApiKeyHashReturnsTheMatchingUser() {
        userRepository.save(new User("carol", "unique-hash-789"));

        var found = userRepository.findByApiKeyHash("unique-hash-789");

        assertThat(found).isPresent();
        assertThat(found.get().getUsername()).isEqualTo("carol");
    }

    @Test
    void findByApiKeyHashIsEmptyForAnUnknownHash() {
        assertThat(userRepository.findByApiKeyHash("no-such-hash")).isEmpty();
    }
}
```

`services/iam/src/test/java/dev/cloudlite/iam/repository/RoleRepositoryTest.java`:

```java
package dev.cloudlite.iam.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cloudlite.iam.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RoleRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void savedRoleCanBeFoundById() {
        Role saved = roleRepository.save(new Role("developers"));

        assertThat(roleRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void existsByNameIsTrueOnceCreated() {
        roleRepository.save(new Role("admins"));

        assertThat(roleRepository.existsByName("admins")).isTrue();
    }

    @Test
    void existsByNameIsFalseForAnUnknownName() {
        assertThat(roleRepository.existsByName("nobody-role")).isFalse();
    }
}
```

`services/iam/src/test/java/dev/cloudlite/iam/repository/PolicyRepositoryTest.java`:

```java
package dev.cloudlite.iam.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cloudlite.iam.domain.Policy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PolicyRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PolicyRepository policyRepository;

    @Test
    void savedPolicyRoundTripsItsJsonDocument() {
        String document = "{\"statements\":[{\"effect\":\"ALLOW\",\"actions\":[\"s3:GetObject\"],\"resources\":[\"arn:cloudlite:s3:::b/*\"]}]}";
        Policy saved = policyRepository.save(new Policy("read-only", document));

        var found = policyRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getDocument()).contains("s3:GetObject");
    }

    @Test
    void existsByNameIsTrueOnceCreated() {
        policyRepository.save(new Policy("full-access", "{\"statements\":[]}"));

        assertThat(policyRepository.existsByName("full-access")).isTrue();
    }

    @Test
    void existsByNameIsFalseForAnUnknownName() {
        assertThat(policyRepository.existsByName("no-such-policy")).isFalse();
    }
}
```

`services/iam/src/test/java/dev/cloudlite/iam/repository/AttachmentRepositoryTest.java`:

```java
package dev.cloudlite.iam.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cloudlite.iam.domain.Policy;
import dev.cloudlite.iam.domain.Role;
import dev.cloudlite.iam.domain.RolePolicy;
import dev.cloudlite.iam.domain.User;
import dev.cloudlite.iam.domain.UserPolicy;
import dev.cloudlite.iam.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AttachmentRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private UserPolicyRepository userPolicyRepository;

    @Autowired
    private RolePolicyRepository rolePolicyRepository;

    @Test
    void findByIdUserIdReturnsTheUsersRoleMemberships() {
        User user = userRepository.save(new User("dave", "hash-dave"));
        Role role = roleRepository.save(new Role("developers"));
        userRoleRepository.save(new UserRole(user.getId(), role.getId()));

        assertThat(userRoleRepository.findByIdUserId(user.getId()))
            .extracting(UserRole::getRoleId)
            .containsExactly(role.getId());
    }

    @Test
    void findByIdUserIdReturnsTheUsersDirectPolicyAttachments() {
        User user = userRepository.save(new User("erin", "hash-erin"));
        Policy policy = policyRepository.save(new Policy("read-only", "{\"statements\":[]}"));
        userPolicyRepository.save(new UserPolicy(user.getId(), policy.getId()));

        assertThat(userPolicyRepository.findByIdUserId(user.getId()))
            .extracting(UserPolicy::getPolicyId)
            .containsExactly(policy.getId());
    }

    @Test
    void findByIdRoleIdReturnsARolesPolicyAttachments() {
        Role role = roleRepository.save(new Role("admins"));
        Policy policy = policyRepository.save(new Policy("full-access", "{\"statements\":[]}"));
        rolePolicyRepository.save(new RolePolicy(role.getId(), policy.getId()));

        assertThat(rolePolicyRepository.findByIdRoleId(role.getId()))
            .extracting(RolePolicy::getPolicyId)
            .containsExactly(policy.getId());
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `cd services/iam && mvn -q test -Dtest=UserRepositoryTest,RoleRepositoryTest,PolicyRepositoryTest,AttachmentRepositoryTest`
Expected: PASS (requires Docker for Testcontainers). This is the first run since the migrations, entities, and repositories are written together — confirm the Flyway migrations apply cleanly against a real Postgres container and all assertions pass.

- [ ] **Step 7: Commit**

```bash
git add services/iam/src/main/resources/db/migration services/iam/src/main/java/dev/cloudlite/iam/domain \
  services/iam/src/main/java/dev/cloudlite/iam/repository services/iam/src/test/java/dev/cloudlite/iam/repository
git commit -m "feat: add Postgres schema, JPA entities, and repositories"
```

---

## Task 3: JSON error handling

**Files:**
- Create: `services/iam/src/main/java/dev/cloudlite/iam/error/IamErrorCode.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/error/IamApiException.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/error/IamErrorResponse.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/error/GlobalExceptionHandler.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/error/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `IamErrorCode` enum with values `USER_NOT_FOUND`, `ROLE_NOT_FOUND`, `POLICY_NOT_FOUND`, `USER_ALREADY_EXISTS`, `ROLE_ALREADY_EXISTS`, `INVALID_API_KEY`, `TOKEN_EXPIRED`, `TOKEN_INVALID`, `INVALID_ARGUMENT`, `NOT_FOUND`, `INTERNAL_ERROR`, each with `status()`/`defaultMessage()`. `IamApiException(IamErrorCode errorCode)` with `getErrorCode()`. `IamErrorResponse` record `(String code, String message)`. `GlobalExceptionHandler` (`@RestControllerAdvice`) with a public no-arg constructor, handling `IamApiException`/`NoHandlerFoundException`/`HttpMessageNotReadableException`/`HttpMediaTypeNotSupportedException`/generic `Exception`, each returning `ResponseEntity<IamErrorResponse>`.
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Create `IamErrorCode`**

```java
package dev.cloudlite.iam.error;

import org.springframework.http.HttpStatus;

public enum IamErrorCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "The specified user does not exist"),
    ROLE_NOT_FOUND(HttpStatus.NOT_FOUND, "The specified role does not exist"),
    POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "The specified policy does not exist"),
    USER_ALREADY_EXISTS(HttpStatus.CONFLICT, "A user with this username already exists"),
    ROLE_ALREADY_EXISTS(HttpStatus.CONFLICT, "A role with this name already exists"),
    INVALID_API_KEY(HttpStatus.UNAUTHORIZED, "The supplied API key is invalid"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "The supplied token has expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "The supplied token is invalid"),
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "Invalid Argument"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The specified resource does not exist"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "We encountered an internal error. Please try again.");

    private final HttpStatus status;
    private final String defaultMessage;

    IamErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
```

- [ ] **Step 2: Create `IamApiException` and `IamErrorResponse`**

```java
package dev.cloudlite.iam.error;

public class IamApiException extends RuntimeException {

    private final IamErrorCode errorCode;

    public IamApiException(IamErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public IamErrorCode getErrorCode() {
        return errorCode;
    }
}
```

```java
package dev.cloudlite.iam.error;

public record IamErrorResponse(String code, String message) {
}
```

- [ ] **Step 3: Write the failing tests for `GlobalExceptionHandler`**

`services/iam/src/test/java/dev/cloudlite/iam/error/GlobalExceptionHandlerTest.java`:

```java
package dev.cloudlite.iam.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void iamApiExceptionIsRenderedAsJsonWithTheRightStatus() {
        IamApiException ex = new IamApiException(IamErrorCode.USER_NOT_FOUND);

        ResponseEntity<IamErrorResponse> response = handler.handleIamApiException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/json");
        assertThat(response.getBody().code()).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void userAlreadyExistsMapsTo409() {
        IamApiException ex = new IamApiException(IamErrorCode.USER_ALREADY_EXISTS);

        ResponseEntity<IamErrorResponse> response = handler.handleIamApiException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void invalidApiKeyMapsTo401() {
        IamApiException ex = new IamApiException(IamErrorCode.INVALID_API_KEY);

        ResponseEntity<IamErrorResponse> response = handler.handleIamApiException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void noHandlerFoundIsRenderedAsAGenericNotFoundError() {
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/no/such/route", new HttpHeaders());

        ResponseEntity<IamErrorResponse> response = handler.handleNoHandlerFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void malformedRequestBodyIsRenderedAs400NotA500() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("could not read request body");

        ResponseEntity<IamErrorResponse> response = handler.handleMessageNotReadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void unsupportedMediaTypeIsRenderedAs400NotA500() {
        HttpMediaTypeNotSupportedException ex =
            new HttpMediaTypeNotSupportedException(MediaType.APPLICATION_XML, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<IamErrorResponse> response = handler.handleMediaTypeNotSupported(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void unexpectedExceptionIsRenderedAsAGenericNonLeakyInternalError() {
        Exception ex = new RuntimeException("column \"foo\" does not exist");

        ResponseEntity<IamErrorResponse> response = handler.handleUnexpected(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).doesNotContain("column");
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `cd services/iam && mvn -q test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — `GlobalExceptionHandler` does not exist (compile error).

- [ ] **Step 5: Implement `GlobalExceptionHandler`**

```java
package dev.cloudlite.iam.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IamApiException.class)
    public ResponseEntity<IamErrorResponse> handleIamApiException(IamApiException ex) {
        return errorResponse(ex.getErrorCode());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<IamErrorResponse> handleNoHandlerFound(NoHandlerFoundException ex) {
        return errorResponse(IamErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<IamErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.debug("iam: malformed request body", ex);
        return errorResponse(IamErrorCode.INVALID_ARGUMENT);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<IamErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.debug("iam: unsupported media type", ex);
        return errorResponse(IamErrorCode.INVALID_ARGUMENT);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<IamErrorResponse> handleUnexpected(Exception ex) {
        log.error("iam: internal error", ex);
        return errorResponse(IamErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<IamErrorResponse> errorResponse(IamErrorCode code) {
        IamErrorResponse body = new IamErrorResponse(code.name(), code.defaultMessage());
        return ResponseEntity.status(code.status())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd services/iam && mvn -q test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add services/iam/src/main/java/dev/cloudlite/iam/error services/iam/src/test/java/dev/cloudlite/iam/error
git commit -m "feat: add JSON error handling"
```

---

## Task 4: Policy evaluation engine

**Files:**
- Create: `services/iam/src/main/java/dev/cloudlite/iam/policy/Effect.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/policy/Decision.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/policy/PolicyStatement.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/policy/PolicyDocument.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/policy/PolicyEngine.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/policy/PolicyEngineTest.java`

**Interfaces:**
- Produces: `Effect` enum (`ALLOW`, `DENY`). `Decision` enum (`ALLOW`, `DENY`). `PolicyStatement` record `(Effect effect, List<String> actions, List<String> resources)`. `PolicyDocument` record `(List<PolicyStatement> statements)`. `PolicyEngine.evaluate(List<PolicyStatement> statements, String action, String resource): Decision` — static method, no constructor needed (utility class).
- Consumes: nothing from earlier tasks. This package has zero Spring/Jakarta imports — pure Java, framework-free, per `architecture.md` §11's requirement to unit-test the policy engine in isolation.

- [ ] **Step 1: Write the failing tests for `PolicyEngine`**

`services/iam/src/test/java/dev/cloudlite/iam/policy/PolicyEngineTest.java`:

```java
package dev.cloudlite.iam.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    @Test
    void explicitAllowGrantsAccess() {
        List<PolicyStatement> statements = List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:GetObject"), List.of("arn:cloudlite:s3:::my-bucket/report.csv")));

        Decision decision = PolicyEngine.evaluate(statements, "s3:GetObject", "arn:cloudlite:s3:::my-bucket/report.csv");

        assertThat(decision).isEqualTo(Decision.ALLOW);
    }

    @Test
    void explicitDenyWithNoAllowRejectsAccess() {
        List<PolicyStatement> statements = List.of(
            new PolicyStatement(Effect.DENY, List.of("s3:DeleteObject"), List.of("arn:cloudlite:s3:::my-bucket/report.csv")));

        Decision decision = PolicyEngine.evaluate(statements, "s3:DeleteObject", "arn:cloudlite:s3:::my-bucket/report.csv");

        assertThat(decision).isEqualTo(Decision.DENY);
    }

    @Test
    void denyOverridesAllowOnConflictingStatements() {
        List<PolicyStatement> statements = List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:DeleteObject"), List.of("arn:cloudlite:s3:::my-bucket/*")),
            new PolicyStatement(Effect.DENY, List.of("s3:DeleteObject"), List.of("arn:cloudlite:s3:::my-bucket/*")));

        Decision decision = PolicyEngine.evaluate(statements, "s3:DeleteObject", "arn:cloudlite:s3:::my-bucket/report.csv");

        assertThat(decision).isEqualTo(Decision.DENY);
    }

    @Test
    void wildcardActionMatches() {
        List<PolicyStatement> statements = List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:*"), List.of("arn:cloudlite:s3:::my-bucket/report.csv")));

        Decision decision = PolicyEngine.evaluate(statements, "s3:PutObject", "arn:cloudlite:s3:::my-bucket/report.csv");

        assertThat(decision).isEqualTo(Decision.ALLOW);
    }

    @Test
    void wildcardResourceMatches() {
        List<PolicyStatement> statements = List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:GetObject"), List.of("arn:cloudlite:s3:::my-bucket/*")));

        Decision decision = PolicyEngine.evaluate(statements, "s3:GetObject", "arn:cloudlite:s3:::my-bucket/nested/report.csv");

        assertThat(decision).isEqualTo(Decision.ALLOW);
    }

    @Test
    void noMatchingStatementIsImplicitlyDenied() {
        List<PolicyStatement> statements = List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:GetObject"), List.of("arn:cloudlite:s3:::other-bucket/*")));

        Decision decision = PolicyEngine.evaluate(statements, "s3:GetObject", "arn:cloudlite:s3:::my-bucket/report.csv");

        assertThat(decision).isEqualTo(Decision.DENY);
    }

    @Test
    void emptyStatementListIsImplicitlyDenied() {
        Decision decision = PolicyEngine.evaluate(List.of(), "s3:GetObject", "arn:cloudlite:s3:::my-bucket/report.csv");

        assertThat(decision).isEqualTo(Decision.DENY);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd services/iam && mvn -q test -Dtest=PolicyEngineTest`
Expected: FAIL — `Effect`, `PolicyStatement`, and `PolicyEngine` do not exist (compile error).

- [ ] **Step 3: Implement `Effect`, `Decision`, `PolicyStatement`, `PolicyDocument`**

```java
package dev.cloudlite.iam.policy;

public enum Effect {
    ALLOW,
    DENY
}
```

```java
package dev.cloudlite.iam.policy;

public enum Decision {
    ALLOW,
    DENY
}
```

```java
package dev.cloudlite.iam.policy;

import java.util.List;

public record PolicyStatement(Effect effect, List<String> actions, List<String> resources) {
}
```

```java
package dev.cloudlite.iam.policy;

import java.util.List;

public record PolicyDocument(List<PolicyStatement> statements) {
}
```

- [ ] **Step 4: Implement `PolicyEngine`**

```java
package dev.cloudlite.iam.policy;

import java.util.List;

public final class PolicyEngine {

    private PolicyEngine() {
    }

    public static Decision evaluate(List<PolicyStatement> statements, String action, String resource) {
        boolean allowed = false;
        for (PolicyStatement statement : statements) {
            if (matchesAny(statement.actions(), action) && matchesAny(statement.resources(), resource)) {
                if (statement.effect() == Effect.DENY) {
                    return Decision.DENY;
                }
                allowed = true;
            }
        }
        return allowed ? Decision.ALLOW : Decision.DENY;
    }

    private static boolean matchesAny(List<String> patterns, String value) {
        for (String pattern : patterns) {
            if (matches(pattern, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String pattern, String value) {
        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(value);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd services/iam && mvn -q test -Dtest=PolicyEngineTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add services/iam/src/main/java/dev/cloudlite/iam/policy services/iam/src/test/java/dev/cloudlite/iam/policy
git commit -m "feat: add the deny-overrides-allow policy evaluation engine"
```

---

## Task 5: User service + controller

**Files:**
- Create: `services/iam/src/main/java/dev/cloudlite/iam/service/ApiKeyGenerator.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/service/NewUser.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/service/UserService.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/dto/CreateUserRequest.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/dto/UserResponse.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/dto/CreatedUserResponse.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/controller/UserController.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/service/ApiKeyGeneratorTest.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/service/UserServiceTest.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/controller/UserControllerTest.java`

**Interfaces:**
- Produces: `ApiKeyGenerator.generate(): String` and `ApiKeyGenerator.hash(String rawKey): String` — static utility, framework-free (Task 7's `AuthService` consumes `hash`). `NewUser` record `(User user, String apiKey)`. `UserService` with constructor `UserService(UserRepository, RoleRepository, PolicyRepository, UserRoleRepository, UserPolicyRepository)` and methods `create(String username): NewUser`, `get(UUID id): User`, `list(): List<User>`, `attachRole(UUID userId, UUID roleId): void`, `attachPolicy(UUID userId, UUID policyId): void`. `UserController` mapped under `/users`.
- Consumes: `User`/`Role`/`Policy`/`UserRole`/`UserPolicy` domain classes and `UserRepository`/`RoleRepository`/`PolicyRepository`/`UserRoleRepository`/`UserPolicyRepository` (Task 2); `IamApiException`/`IamErrorCode` and `GlobalExceptionHandler` (Task 3).

- [ ] **Step 1: Create `ApiKeyGenerator`**

```java
package dev.cloudlite.iam.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class ApiKeyGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyGenerator() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashed) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

- [ ] **Step 2: Write the failing test for `ApiKeyGenerator`**

`services/iam/src/test/java/dev/cloudlite/iam/service/ApiKeyGeneratorTest.java`:

```java
package dev.cloudlite.iam.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeyGeneratorTest {

    @Test
    void generateProducesANonBlankKey() {
        assertThat(ApiKeyGenerator.generate()).isNotBlank();
    }

    @Test
    void generateProducesDistinctKeysOnEachCall() {
        assertThat(ApiKeyGenerator.generate()).isNotEqualTo(ApiKeyGenerator.generate());
    }

    @Test
    void hashIsDeterministicForTheSameInput() {
        assertThat(ApiKeyGenerator.hash("same-key")).isEqualTo(ApiKeyGenerator.hash("same-key"));
    }

    @Test
    void hashDiffersForDifferentInput() {
        assertThat(ApiKeyGenerator.hash("key-one")).isNotEqualTo(ApiKeyGenerator.hash("key-two"));
    }

    @Test
    void hashNeverReturnsTheRawInput() {
        assertThat(ApiKeyGenerator.hash("my-raw-key")).isNotEqualTo("my-raw-key");
    }
}
```

- [ ] **Step 3: Run the test to verify it passes**

Run: `cd services/iam && mvn -q test -Dtest=ApiKeyGeneratorTest`
Expected: PASS (this class has no dependency to stub, so no red-green cycle is needed — it's already implemented).

- [ ] **Step 4: Create `NewUser` and `UserService`**

`services/iam/src/main/java/dev/cloudlite/iam/service/NewUser.java`:

```java
package dev.cloudlite.iam.service;

import dev.cloudlite.iam.domain.User;

public record NewUser(User user, String apiKey) {
}
```

`services/iam/src/main/java/dev/cloudlite/iam/service/UserService.java`:

```java
package dev.cloudlite.iam.service;

import dev.cloudlite.iam.domain.User;
import dev.cloudlite.iam.domain.UserPolicy;
import dev.cloudlite.iam.domain.UserRole;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.repository.PolicyRepository;
import dev.cloudlite.iam.repository.RoleRepository;
import dev.cloudlite.iam.repository.UserPolicyRepository;
import dev.cloudlite.iam.repository.UserRepository;
import dev.cloudlite.iam.repository.UserRoleRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PolicyRepository policies;
    private final UserRoleRepository userRoles;
    private final UserPolicyRepository userPolicies;

    public UserService(
            UserRepository users,
            RoleRepository roles,
            PolicyRepository policies,
            UserRoleRepository userRoles,
            UserPolicyRepository userPolicies) {
        this.users = users;
        this.roles = roles;
        this.policies = policies;
        this.userRoles = userRoles;
        this.userPolicies = userPolicies;
    }

    public NewUser create(String username) {
        if (users.existsByUsername(username)) {
            throw new IamApiException(IamErrorCode.USER_ALREADY_EXISTS);
        }
        String apiKey = ApiKeyGenerator.generate();
        User user = users.save(new User(username, ApiKeyGenerator.hash(apiKey)));
        return new NewUser(user, apiKey);
    }

    public User get(UUID id) {
        return users.findById(id).orElseThrow(() -> new IamApiException(IamErrorCode.USER_NOT_FOUND));
    }

    public List<User> list() {
        return users.findAll();
    }

    public void attachRole(UUID userId, UUID roleId) {
        if (!users.existsById(userId)) {
            throw new IamApiException(IamErrorCode.USER_NOT_FOUND);
        }
        if (!roles.existsById(roleId)) {
            throw new IamApiException(IamErrorCode.ROLE_NOT_FOUND);
        }
        userRoles.save(new UserRole(userId, roleId));
    }

    public void attachPolicy(UUID userId, UUID policyId) {
        if (!users.existsById(userId)) {
            throw new IamApiException(IamErrorCode.USER_NOT_FOUND);
        }
        if (!policies.existsById(policyId)) {
            throw new IamApiException(IamErrorCode.POLICY_NOT_FOUND);
        }
        userPolicies.save(new UserPolicy(userId, policyId));
    }
}
```

- [ ] **Step 5: Write the failing test for `UserService`**

`services/iam/src/test/java/dev/cloudlite/iam/service/UserServiceTest.java`:

```java
package dev.cloudlite.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cloudlite.iam.domain.User;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.repository.PolicyRepository;
import dev.cloudlite.iam.repository.RoleRepository;
import dev.cloudlite.iam.repository.UserPolicyRepository;
import dev.cloudlite.iam.repository.UserRepository;
import dev.cloudlite.iam.repository.UserRoleRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserServiceTest {

    private UserRepository users;
    private RoleRepository roles;
    private PolicyRepository policies;
    private UserRoleRepository userRoles;
    private UserPolicyRepository userPolicies;
    private UserService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        roles = mock(RoleRepository.class);
        policies = mock(PolicyRepository.class);
        userRoles = mock(UserRoleRepository.class);
        userPolicies = mock(UserPolicyRepository.class);
        service = new UserService(users, roles, policies, userRoles, userPolicies);
    }

    @Test
    void createRejectsADuplicateUsername() {
        when(users.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.create("alice"))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.USER_ALREADY_EXISTS);
    }

    @Test
    void createSavesANewUserWithAHashedApiKey() {
        when(users.existsByUsername("alice")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NewUser result = service.create("alice");

        assertThat(result.user().getUsername()).isEqualTo("alice");
        assertThat(result.apiKey()).isNotBlank();
        assertThat(result.user().getApiKeyHash()).isNotEqualTo(result.apiKey());
    }

    @Test
    void getThrowsWhenTheUserIsMissing() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.USER_NOT_FOUND);
    }

    @Test
    void attachRoleThrowsWhenTheUserIsMissing() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(users.existsById(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.attachRole(userId, roleId))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.USER_NOT_FOUND);
    }

    @Test
    void attachRoleThrowsWhenTheRoleIsMissing() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(users.existsById(userId)).thenReturn(true);
        when(roles.existsById(roleId)).thenReturn(false);

        assertThatThrownBy(() -> service.attachRole(userId, roleId))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.ROLE_NOT_FOUND);
    }

    @Test
    void attachRoleSavesTheMembershipWhenBothExist() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(users.existsById(userId)).thenReturn(true);
        when(roles.existsById(roleId)).thenReturn(true);

        service.attachRole(userId, roleId);

        verify(userRoles).save(any());
    }

    @Test
    void attachPolicyThrowsWhenThePolicyIsMissing() {
        UUID userId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        when(users.existsById(userId)).thenReturn(true);
        when(policies.existsById(policyId)).thenReturn(false);

        assertThatThrownBy(() -> service.attachPolicy(userId, policyId))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.POLICY_NOT_FOUND);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd services/iam && mvn -q test -Dtest=UserServiceTest`
Expected: PASS

- [ ] **Step 7: Create the DTOs and `UserController`**

`services/iam/src/main/java/dev/cloudlite/iam/dto/CreateUserRequest.java`:

```java
package dev.cloudlite.iam.dto;

public record CreateUserRequest(String username) {
}
```

`services/iam/src/main/java/dev/cloudlite/iam/dto/UserResponse.java`:

```java
package dev.cloudlite.iam.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(UUID id, String username, OffsetDateTime createdAt) {
}
```

`services/iam/src/main/java/dev/cloudlite/iam/dto/CreatedUserResponse.java`:

```java
package dev.cloudlite.iam.dto;

import java.util.UUID;

public record CreatedUserResponse(UUID id, String username, String apiKey) {
}
```

`services/iam/src/main/java/dev/cloudlite/iam/controller/UserController.java`:

```java
package dev.cloudlite.iam.controller;

import dev.cloudlite.iam.domain.User;
import dev.cloudlite.iam.dto.CreateUserRequest;
import dev.cloudlite.iam.dto.CreatedUserResponse;
import dev.cloudlite.iam.dto.UserResponse;
import dev.cloudlite.iam.service.NewUser;
import dev.cloudlite.iam.service.UserService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<CreatedUserResponse> create(@RequestBody CreateUserRequest request) {
        NewUser newUser = userService.create(request.username());
        CreatedUserResponse body =
            new CreatedUserResponse(newUser.user().getId(), newUser.user().getUsername(), newUser.apiKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping
    public ResponseEntity<List<UserResponse>> list() {
        List<UserResponse> body = userService.list().stream()
            .map(u -> new UserResponse(u.getId(), u.getUsername(), u.getCreatedAt()))
            .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> get(@PathVariable UUID id) {
        User user = userService.get(id);
        return ResponseEntity.ok(new UserResponse(user.getId(), user.getUsername(), user.getCreatedAt()));
    }

    @PostMapping("/{id}/roles/{roleId}")
    public ResponseEntity<Void> attachRole(@PathVariable UUID id, @PathVariable UUID roleId) {
        userService.attachRole(id, roleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/policies/{policyId}")
    public ResponseEntity<Void> attachPolicy(@PathVariable UUID id, @PathVariable UUID policyId) {
        userService.attachPolicy(id, policyId);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 8: Write the failing test for `UserController`**

`services/iam/src/test/java/dev/cloudlite/iam/controller/UserControllerTest.java`:

```java
package dev.cloudlite.iam.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.iam.domain.User;
import dev.cloudlite.iam.error.GlobalExceptionHandler;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.service.NewUser;
import dev.cloudlite.iam.service.UserService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserController.class)
@Import(GlobalExceptionHandler.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @Test
    void createReturns201WithTheRawApiKey() throws Exception {
        User user = new User("alice", "hashed");
        given(userService.create("alice")).willReturn(new NewUser(user, "raw-key-123"));

        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\"}"))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("raw-key-123")));
    }

    @Test
    void createReturns409WhenTheUsernameAlreadyExists() throws Exception {
        given(userService.create("alice")).willThrow(new IamApiException(IamErrorCode.USER_ALREADY_EXISTS));

        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\"}"))
            .andExpect(status().isConflict())
            .andExpect(content().string(containsString("USER_ALREADY_EXISTS")));
    }

    @Test
    void listReturns200WithAJsonBody() throws Exception {
        given(userService.list()).willReturn(List.of(new User("alice", "hashed")));

        mockMvc.perform(get("/users"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("alice")));
    }

    @Test
    void getReturns404WhenTheUserIsMissing() throws Exception {
        UUID id = UUID.randomUUID();
        given(userService.get(id)).willThrow(new IamApiException(IamErrorCode.USER_NOT_FOUND));

        mockMvc.perform(get("/users/" + id))
            .andExpect(status().isNotFound());
    }

    @Test
    void attachRoleReturns204OnSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();

        mockMvc.perform(post("/users/" + id + "/roles/" + roleId))
            .andExpect(status().isNoContent());
    }

    @Test
    void attachPolicyReturns404WhenThePolicyIsMissing() throws Exception {
        UUID id = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        doThrow(new IamApiException(IamErrorCode.POLICY_NOT_FOUND))
            .when(userService).attachPolicy(id, policyId);

        mockMvc.perform(post("/users/" + id + "/policies/" + policyId))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd services/iam && mvn -q test -Dtest=UserControllerTest`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add services/iam/src/main/java/dev/cloudlite/iam/service services/iam/src/main/java/dev/cloudlite/iam/dto \
  services/iam/src/main/java/dev/cloudlite/iam/controller/UserController.java \
  services/iam/src/test/java/dev/cloudlite/iam/service services/iam/src/test/java/dev/cloudlite/iam/controller/UserControllerTest.java
git commit -m "feat: add user service, admin CRUD endpoints, and attachment endpoints"
```

---

## Task 6: Role and policy services + controllers

**Files:**
- Create: `services/iam/src/main/java/dev/cloudlite/iam/service/RoleService.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/service/PolicyService.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/dto/CreateRoleRequest.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/dto/RoleResponse.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/dto/CreatePolicyRequest.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/dto/PolicyResponse.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/controller/RoleController.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/controller/PolicyController.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/service/RoleServiceTest.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/service/PolicyServiceTest.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/controller/RoleControllerTest.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/controller/PolicyControllerTest.java`

**Interfaces:**
- Produces: `RoleService` with constructor `RoleService(RoleRepository, PolicyRepository, RolePolicyRepository)` and methods `create(String name): Role`, `get(UUID id): Role`, `list(): List<Role>`, `attachPolicy(UUID roleId, UUID policyId): void`. `PolicyService` with constructor `PolicyService(PolicyRepository, ObjectMapper)` and methods `create(String name, PolicyDocument document): Policy`, `get(UUID id): Policy`, `list(): List<Policy>`, `parseDocument(Policy policy): PolicyDocument` (this last method is `public` because Task 7's `AuthorizationService` consumes it directly, to avoid a second, divergent JSON-parsing implementation). `RoleController` mapped under `/roles`. `PolicyController` mapped under `/policies`.
- Consumes: `Role`/`Policy`/`RolePolicy` domain classes and `RoleRepository`/`PolicyRepository`/`RolePolicyRepository` (Task 2); `IamApiException`/`IamErrorCode`/`GlobalExceptionHandler` (Task 3); `PolicyDocument` (Task 4).

Note: the spec's `IamErrorCode` set (Task 3) has no `POLICY_ALREADY_EXISTS` value even though `policies.name` is `UNIQUE`. `PolicyService.create` below reuses `INVALID_ARGUMENT` (400) for a duplicate policy name — this is a deliberate plan decision, not an oversight, per the Global Constraints note above.

- [ ] **Step 1: Create `RoleService`**

```java
package dev.cloudlite.iam.service;

import dev.cloudlite.iam.domain.Role;
import dev.cloudlite.iam.domain.RolePolicy;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.repository.PolicyRepository;
import dev.cloudlite.iam.repository.RolePolicyRepository;
import dev.cloudlite.iam.repository.RoleRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RoleService {

    private final RoleRepository roles;
    private final PolicyRepository policies;
    private final RolePolicyRepository rolePolicies;

    public RoleService(RoleRepository roles, PolicyRepository policies, RolePolicyRepository rolePolicies) {
        this.roles = roles;
        this.policies = policies;
        this.rolePolicies = rolePolicies;
    }

    public Role create(String name) {
        if (roles.existsByName(name)) {
            throw new IamApiException(IamErrorCode.ROLE_ALREADY_EXISTS);
        }
        return roles.save(new Role(name));
    }

    public Role get(UUID id) {
        return roles.findById(id).orElseThrow(() -> new IamApiException(IamErrorCode.ROLE_NOT_FOUND));
    }

    public List<Role> list() {
        return roles.findAll();
    }

    public void attachPolicy(UUID roleId, UUID policyId) {
        if (!roles.existsById(roleId)) {
            throw new IamApiException(IamErrorCode.ROLE_NOT_FOUND);
        }
        if (!policies.existsById(policyId)) {
            throw new IamApiException(IamErrorCode.POLICY_NOT_FOUND);
        }
        rolePolicies.save(new RolePolicy(roleId, policyId));
    }
}
```

- [ ] **Step 2: Write the failing test for `RoleService`**

`services/iam/src/test/java/dev/cloudlite/iam/service/RoleServiceTest.java`:

```java
package dev.cloudlite.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cloudlite.iam.domain.Role;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.repository.PolicyRepository;
import dev.cloudlite.iam.repository.RolePolicyRepository;
import dev.cloudlite.iam.repository.RoleRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoleServiceTest {

    private RoleRepository roles;
    private PolicyRepository policies;
    private RolePolicyRepository rolePolicies;
    private RoleService service;

    @BeforeEach
    void setUp() {
        roles = mock(RoleRepository.class);
        policies = mock(PolicyRepository.class);
        rolePolicies = mock(RolePolicyRepository.class);
        service = new RoleService(roles, policies, rolePolicies);
    }

    @Test
    void createRejectsADuplicateName() {
        when(roles.existsByName("developers")).thenReturn(true);

        assertThatThrownBy(() -> service.create("developers"))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.ROLE_ALREADY_EXISTS);
    }

    @Test
    void createSavesANewRole() {
        when(roles.existsByName("developers")).thenReturn(false);
        when(roles.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Role role = service.create("developers");

        assertThat(role.getName()).isEqualTo("developers");
    }

    @Test
    void getThrowsWhenTheRoleIsMissing() {
        UUID id = UUID.randomUUID();
        when(roles.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.ROLE_NOT_FOUND);
    }

    @Test
    void attachPolicyThrowsWhenTheRoleIsMissing() {
        UUID roleId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        when(roles.existsById(roleId)).thenReturn(false);

        assertThatThrownBy(() -> service.attachPolicy(roleId, policyId))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.ROLE_NOT_FOUND);
    }

    @Test
    void attachPolicyThrowsWhenThePolicyIsMissing() {
        UUID roleId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        when(roles.existsById(roleId)).thenReturn(true);
        when(policies.existsById(policyId)).thenReturn(false);

        assertThatThrownBy(() -> service.attachPolicy(roleId, policyId))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.POLICY_NOT_FOUND);
    }

    @Test
    void attachPolicySavesTheAttachmentWhenBothExist() {
        UUID roleId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        when(roles.existsById(roleId)).thenReturn(true);
        when(policies.existsById(policyId)).thenReturn(true);

        service.attachPolicy(roleId, policyId);

        verify(rolePolicies).save(any());
    }
}
```

- [ ] **Step 3: Run the test to verify it passes**

Run: `cd services/iam && mvn -q test -Dtest=RoleServiceTest`
Expected: PASS

- [ ] **Step 4: Create `PolicyService`**

```java
package dev.cloudlite.iam.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cloudlite.iam.domain.Policy;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.policy.PolicyDocument;
import dev.cloudlite.iam.repository.PolicyRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PolicyService {

    private final PolicyRepository policies;
    private final ObjectMapper objectMapper;

    public PolicyService(PolicyRepository policies, ObjectMapper objectMapper) {
        this.policies = policies;
        this.objectMapper = objectMapper;
    }

    public Policy create(String name, PolicyDocument document) {
        if (policies.existsByName(name)) {
            throw new IamApiException(IamErrorCode.INVALID_ARGUMENT);
        }
        return policies.save(new Policy(name, toJson(document)));
    }

    public Policy get(UUID id) {
        return policies.findById(id).orElseThrow(() -> new IamApiException(IamErrorCode.POLICY_NOT_FOUND));
    }

    public List<Policy> list() {
        return policies.findAll();
    }

    public PolicyDocument parseDocument(Policy policy) {
        try {
            return objectMapper.readValue(policy.getDocument(), PolicyDocument.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored policy document is not valid JSON: " + policy.getId(), e);
        }
    }

    private String toJson(PolicyDocument document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IamApiException(IamErrorCode.INVALID_ARGUMENT);
        }
    }
}
```

- [ ] **Step 5: Write the failing test for `PolicyService`**

`services/iam/src/test/java/dev/cloudlite/iam/service/PolicyServiceTest.java`:

```java
package dev.cloudlite.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cloudlite.iam.domain.Policy;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.policy.Effect;
import dev.cloudlite.iam.policy.PolicyDocument;
import dev.cloudlite.iam.policy.PolicyStatement;
import dev.cloudlite.iam.repository.PolicyRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyServiceTest {

    private PolicyRepository policies;
    private PolicyService service;

    @BeforeEach
    void setUp() {
        policies = mock(PolicyRepository.class);
        service = new PolicyService(policies, new ObjectMapper());
    }

    @Test
    void createRejectsADuplicatePolicyNameAsInvalidArgument() {
        when(policies.existsByName("read-only")).thenReturn(true);

        assertThatThrownBy(() -> service.create("read-only", new PolicyDocument(List.of())))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void createSerializesTheDocumentToJsonBeforeSaving() {
        when(policies.existsByName("read-only")).thenReturn(false);
        when(policies.save(any(Policy.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Policy saved = service.create("read-only", new PolicyDocument(List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:GetObject"), List.of("arn:cloudlite:s3:::my-bucket/*")))));

        assertThat(saved.getDocument()).contains("ALLOW").contains("s3:GetObject");
    }

    @Test
    void parseDocumentRoundTripsTheStoredJson() {
        Policy policy = new Policy("read-only",
            "{\"statements\":[{\"effect\":\"ALLOW\",\"actions\":[\"s3:GetObject\"],\"resources\":[\"arn:cloudlite:s3:::b/*\"]}]}");

        PolicyDocument document = service.parseDocument(policy);

        assertThat(document.statements()).hasSize(1);
        assertThat(document.statements().get(0).effect()).isEqualTo(Effect.ALLOW);
        assertThat(document.statements().get(0).actions()).containsExactly("s3:GetObject");
    }

    @Test
    void getThrowsWhenThePolicyIsMissing() {
        UUID id = UUID.randomUUID();
        when(policies.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.POLICY_NOT_FOUND);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd services/iam && mvn -q test -Dtest=PolicyServiceTest`
Expected: PASS

- [ ] **Step 7: Create the DTOs and controllers**

`services/iam/src/main/java/dev/cloudlite/iam/dto/CreateRoleRequest.java`:

```java
package dev.cloudlite.iam.dto;

public record CreateRoleRequest(String name) {
}
```

`services/iam/src/main/java/dev/cloudlite/iam/dto/RoleResponse.java`:

```java
package dev.cloudlite.iam.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RoleResponse(UUID id, String name, OffsetDateTime createdAt) {
}
```

`services/iam/src/main/java/dev/cloudlite/iam/dto/CreatePolicyRequest.java`:

```java
package dev.cloudlite.iam.dto;

import dev.cloudlite.iam.policy.PolicyDocument;

public record CreatePolicyRequest(String name, PolicyDocument document) {
}
```

`services/iam/src/main/java/dev/cloudlite/iam/dto/PolicyResponse.java`:

```java
package dev.cloudlite.iam.dto;

import dev.cloudlite.iam.policy.PolicyDocument;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PolicyResponse(UUID id, String name, PolicyDocument document, OffsetDateTime createdAt) {
}
```

`services/iam/src/main/java/dev/cloudlite/iam/controller/RoleController.java`:

```java
package dev.cloudlite.iam.controller;

import dev.cloudlite.iam.domain.Role;
import dev.cloudlite.iam.dto.CreateRoleRequest;
import dev.cloudlite.iam.dto.RoleResponse;
import dev.cloudlite.iam.service.RoleService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @PostMapping
    public ResponseEntity<RoleResponse> create(@RequestBody CreateRoleRequest request) {
        Role role = roleService.create(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(role));
    }

    @GetMapping
    public ResponseEntity<List<RoleResponse>> list() {
        return ResponseEntity.ok(roleService.list().stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoleResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(toResponse(roleService.get(id)));
    }

    @PostMapping("/{id}/policies/{policyId}")
    public ResponseEntity<Void> attachPolicy(@PathVariable UUID id, @PathVariable UUID policyId) {
        roleService.attachPolicy(id, policyId);
        return ResponseEntity.noContent().build();
    }

    private RoleResponse toResponse(Role role) {
        return new RoleResponse(role.getId(), role.getName(), role.getCreatedAt());
    }
}
```

`services/iam/src/main/java/dev/cloudlite/iam/controller/PolicyController.java`:

```java
package dev.cloudlite.iam.controller;

import dev.cloudlite.iam.domain.Policy;
import dev.cloudlite.iam.dto.CreatePolicyRequest;
import dev.cloudlite.iam.dto.PolicyResponse;
import dev.cloudlite.iam.service.PolicyService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/policies")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping
    public ResponseEntity<PolicyResponse> create(@RequestBody CreatePolicyRequest request) {
        Policy policy = policyService.create(request.name(), request.document());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(policy));
    }

    @GetMapping
    public ResponseEntity<List<PolicyResponse>> list() {
        return ResponseEntity.ok(policyService.list().stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PolicyResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(toResponse(policyService.get(id)));
    }

    private PolicyResponse toResponse(Policy policy) {
        return new PolicyResponse(
            policy.getId(), policy.getName(), policyService.parseDocument(policy), policy.getCreatedAt());
    }
}
```

- [ ] **Step 8: Write the failing tests for `RoleController` and `PolicyController`**

`services/iam/src/test/java/dev/cloudlite/iam/controller/RoleControllerTest.java`:

```java
package dev.cloudlite.iam.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.iam.domain.Role;
import dev.cloudlite.iam.error.GlobalExceptionHandler;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.service.RoleService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RoleController.class)
@Import(GlobalExceptionHandler.class)
class RoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RoleService roleService;

    @Test
    void createReturns201WithTheNewRole() throws Exception {
        given(roleService.create("developers")).willReturn(new Role("developers"));

        mockMvc.perform(post("/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"developers\"}"))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("developers")));
    }

    @Test
    void createReturns409WhenTheNameAlreadyExists() throws Exception {
        given(roleService.create("developers")).willThrow(new IamApiException(IamErrorCode.ROLE_ALREADY_EXISTS));

        mockMvc.perform(post("/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"developers\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void listReturns200WithAJsonBody() throws Exception {
        given(roleService.list()).willReturn(List.of(new Role("developers")));

        mockMvc.perform(get("/roles"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("developers")));
    }

    @Test
    void getReturns404WhenTheRoleIsMissing() throws Exception {
        UUID id = UUID.randomUUID();
        given(roleService.get(id)).willThrow(new IamApiException(IamErrorCode.ROLE_NOT_FOUND));

        mockMvc.perform(get("/roles/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void attachPolicyReturns204OnSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();

        mockMvc.perform(post("/roles/" + id + "/policies/" + policyId))
            .andExpect(status().isNoContent());
    }
}
```

`services/iam/src/test/java/dev/cloudlite/iam/controller/PolicyControllerTest.java`:

```java
package dev.cloudlite.iam.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.iam.domain.Policy;
import dev.cloudlite.iam.error.GlobalExceptionHandler;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.policy.Effect;
import dev.cloudlite.iam.policy.PolicyDocument;
import dev.cloudlite.iam.policy.PolicyStatement;
import dev.cloudlite.iam.service.PolicyService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PolicyController.class)
@Import(GlobalExceptionHandler.class)
class PolicyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PolicyService policyService;

    @Test
    void createReturns201WithTheNewPolicy() throws Exception {
        Policy policy = new Policy("read-only", "{\"statements\":[]}");
        given(policyService.create("read-only", new PolicyDocument(List.of()))).willReturn(policy);
        given(policyService.parseDocument(policy)).willReturn(new PolicyDocument(List.of()));

        mockMvc.perform(post("/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"read-only\",\"document\":{\"statements\":[]}}"))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("read-only")));
    }

    @Test
    void createReturns400WhenTheNameAlreadyExists() throws Exception {
        given(policyService.create("read-only", new PolicyDocument(List.of())))
            .willThrow(new IamApiException(IamErrorCode.INVALID_ARGUMENT));

        mockMvc.perform(post("/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"read-only\",\"document\":{\"statements\":[]}}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listReturns200WithAJsonBody() throws Exception {
        Policy policy = new Policy("read-only", "{\"statements\":[]}");
        given(policyService.list()).willReturn(List.of(policy));
        given(policyService.parseDocument(policy)).willReturn(new PolicyDocument(List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:GetObject"), List.of("arn:cloudlite:s3:::b/*")))));

        mockMvc.perform(get("/policies"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("read-only")));
    }

    @Test
    void getReturns404WhenThePolicyIsMissing() throws Exception {
        UUID id = UUID.randomUUID();
        given(policyService.get(id)).willThrow(new IamApiException(IamErrorCode.POLICY_NOT_FOUND));

        mockMvc.perform(get("/policies/" + id)).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd services/iam && mvn -q test -Dtest=RoleControllerTest,PolicyControllerTest`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add services/iam/src/main/java/dev/cloudlite/iam/service/RoleService.java \
  services/iam/src/main/java/dev/cloudlite/iam/service/PolicyService.java \
  services/iam/src/main/java/dev/cloudlite/iam/dto/CreateRoleRequest.java \
  services/iam/src/main/java/dev/cloudlite/iam/dto/RoleResponse.java \
  services/iam/src/main/java/dev/cloudlite/iam/dto/CreatePolicyRequest.java \
  services/iam/src/main/java/dev/cloudlite/iam/dto/PolicyResponse.java \
  services/iam/src/main/java/dev/cloudlite/iam/controller/RoleController.java \
  services/iam/src/main/java/dev/cloudlite/iam/controller/PolicyController.java \
  services/iam/src/test/java/dev/cloudlite/iam/service/RoleServiceTest.java \
  services/iam/src/test/java/dev/cloudlite/iam/service/PolicyServiceTest.java \
  services/iam/src/test/java/dev/cloudlite/iam/controller/RoleControllerTest.java \
  services/iam/src/test/java/dev/cloudlite/iam/controller/PolicyControllerTest.java
git commit -m "feat: add role and policy services with admin CRUD endpoints"
```

---

## Task 7: Auth flow — token issuance + authorization decision

**Files:**
- Create: `services/iam/src/main/java/dev/cloudlite/iam/service/TokenResult.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/service/AuthService.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/service/AuthorizationService.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/dto/TokenResponse.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/dto/AuthorizeRequest.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/dto/AuthorizeResponse.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/controller/AuthController.java`
- Create: `services/iam/src/main/java/dev/cloudlite/iam/controller/AuthorizationController.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/service/AuthServiceTest.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/service/AuthorizationServiceTest.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/controller/AuthControllerTest.java`
- Test: `services/iam/src/test/java/dev/cloudlite/iam/controller/AuthorizationControllerTest.java`

**Interfaces:**
- Produces: `TokenResult` record `(String token, long expiresInSeconds)`. `AuthService` with constructor `AuthService(UserRepository users, String secret, long expirySeconds)` (the last two parameters are `@Value`-injected from `iam.jwt.secret`/`iam.jwt.expiry-seconds`) and methods `issueToken(String rawApiKey): TokenResult`, `parseUserId(String token): UUID`. `AuthorizationService` with constructor `AuthorizationService(UserRoleRepository, UserPolicyRepository, RolePolicyRepository, PolicyRepository, PolicyService)` and method `authorize(UUID userId, String action, String resource): Decision`. `AuthController` mapped to `POST /auth/token`. `AuthorizationController` mapped to `POST /authorize`.
- Consumes: `ApiKeyGenerator.hash` (Task 5); `UserRepository`/`UserRoleRepository`/`UserPolicyRepository`/`RolePolicyRepository`/`PolicyRepository` and the `UserRole`/`UserPolicy`/`RolePolicy`/`Policy` domain classes (Task 2); `IamApiException`/`IamErrorCode`/`GlobalExceptionHandler` (Task 3); `Decision`/`PolicyEngine`/`PolicyStatement` (Task 4); `PolicyService.parseDocument` (Task 6).

- [ ] **Step 1: Create `TokenResult` and `AuthService`**

`services/iam/src/main/java/dev/cloudlite/iam/service/TokenResult.java`:

```java
package dev.cloudlite.iam.service;

public record TokenResult(String token, long expiresInSeconds) {
}
```

`services/iam/src/main/java/dev/cloudlite/iam/service/AuthService.java`:

```java
package dev.cloudlite.iam.service;

import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.repository.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository users;
    private final SecretKey signingKey;
    private final long expirySeconds;

    public AuthService(
            UserRepository users,
            @Value("${iam.jwt.secret}") String secret,
            @Value("${iam.jwt.expiry-seconds}") long expirySeconds) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("iam.jwt.secret must be at least 32 bytes");
        }
        this.users = users;
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirySeconds = expirySeconds;
    }

    public TokenResult issueToken(String rawApiKey) {
        String hash = ApiKeyGenerator.hash(rawApiKey);
        var user = users.findByApiKeyHash(hash)
            .orElseThrow(() -> new IamApiException(IamErrorCode.INVALID_API_KEY));

        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirySeconds * 1000);
        String token = Jwts.builder()
            .subject(user.getId().toString())
            .issuedAt(now)
            .expiration(expiry)
            .signWith(signingKey)
            .compact();

        return new TokenResult(token, expirySeconds);
    }

    public UUID parseUserId(String token) {
        try {
            String subject = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
            return UUID.fromString(subject);
        } catch (ExpiredJwtException e) {
            throw new IamApiException(IamErrorCode.TOKEN_EXPIRED);
        } catch (JwtException e) {
            throw new IamApiException(IamErrorCode.TOKEN_INVALID);
        }
    }
}
```

- [ ] **Step 2: Write the failing test for `AuthService`**

`services/iam/src/test/java/dev/cloudlite/iam/service/AuthServiceTest.java`:

```java
package dev.cloudlite.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.cloudlite.iam.domain.User;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

    private static final String SECRET = "test-only-signing-secret-at-least-32-bytes-long";

    private UserRepository users;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        service = new AuthService(users, SECRET, 900);
    }

    @Test
    void constructorRejectsATooShortSecret() {
        assertThatThrownBy(() -> new AuthService(users, "too-short", 900))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void issueTokenRejectsAnUnknownApiKey() {
        when(users.findByApiKeyHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueToken("unknown-key"))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.INVALID_API_KEY);
    }

    @Test
    void issueTokenThenParseUserIdRoundTripsTheUserId() {
        User user = new User("alice", ApiKeyGenerator.hash("raw-key"));
        when(users.findByApiKeyHash(ApiKeyGenerator.hash("raw-key"))).thenReturn(Optional.of(user));

        TokenResult result = service.issueToken("raw-key");
        UUID parsed = service.parseUserId(result.token());

        assertThat(parsed).isEqualTo(user.getId());
        assertThat(result.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void parseUserIdRejectsAMalformedToken() {
        assertThatThrownBy(() -> service.parseUserId("not-a-real-jwt"))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.TOKEN_INVALID);
    }
}
```

- [ ] **Step 3: Run the test to verify it passes**

Run: `cd services/iam && mvn -q test -Dtest=AuthServiceTest`
Expected: PASS

- [ ] **Step 4: Create `AuthorizationService`**

```java
package dev.cloudlite.iam.service;

import dev.cloudlite.iam.domain.Policy;
import dev.cloudlite.iam.domain.RolePolicy;
import dev.cloudlite.iam.domain.UserPolicy;
import dev.cloudlite.iam.domain.UserRole;
import dev.cloudlite.iam.policy.Decision;
import dev.cloudlite.iam.policy.PolicyEngine;
import dev.cloudlite.iam.policy.PolicyStatement;
import dev.cloudlite.iam.repository.PolicyRepository;
import dev.cloudlite.iam.repository.RolePolicyRepository;
import dev.cloudlite.iam.repository.UserPolicyRepository;
import dev.cloudlite.iam.repository.UserRoleRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {

    private final UserRoleRepository userRoles;
    private final UserPolicyRepository userPolicies;
    private final RolePolicyRepository rolePolicies;
    private final PolicyRepository policies;
    private final PolicyService policyService;

    public AuthorizationService(
            UserRoleRepository userRoles,
            UserPolicyRepository userPolicies,
            RolePolicyRepository rolePolicies,
            PolicyRepository policies,
            PolicyService policyService) {
        this.userRoles = userRoles;
        this.userPolicies = userPolicies;
        this.rolePolicies = rolePolicies;
        this.policies = policies;
        this.policyService = policyService;
    }

    public Decision authorize(UUID userId, String action, String resource) {
        List<PolicyStatement> statements = resolveEffectiveStatements(userId);
        return PolicyEngine.evaluate(statements, action, resource);
    }

    private List<PolicyStatement> resolveEffectiveStatements(UUID userId) {
        Set<UUID> policyIds = new LinkedHashSet<>();
        for (UserPolicy userPolicy : userPolicies.findByIdUserId(userId)) {
            policyIds.add(userPolicy.getPolicyId());
        }
        for (UserRole userRole : userRoles.findByIdUserId(userId)) {
            for (RolePolicy rolePolicy : rolePolicies.findByIdRoleId(userRole.getRoleId())) {
                policyIds.add(rolePolicy.getPolicyId());
            }
        }

        List<PolicyStatement> statements = new ArrayList<>();
        for (UUID policyId : policyIds) {
            policies.findById(policyId).ifPresent(policy -> statements.addAll(toStatements(policy)));
        }
        return statements;
    }

    private List<PolicyStatement> toStatements(Policy policy) {
        return policyService.parseDocument(policy).statements();
    }
}
```

- [ ] **Step 5: Write the failing test for `AuthorizationService`**

`services/iam/src/test/java/dev/cloudlite/iam/service/AuthorizationServiceTest.java`:

```java
package dev.cloudlite.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.cloudlite.iam.domain.Policy;
import dev.cloudlite.iam.domain.RolePolicy;
import dev.cloudlite.iam.domain.UserPolicy;
import dev.cloudlite.iam.domain.UserRole;
import dev.cloudlite.iam.policy.Decision;
import dev.cloudlite.iam.policy.Effect;
import dev.cloudlite.iam.policy.PolicyDocument;
import dev.cloudlite.iam.policy.PolicyStatement;
import dev.cloudlite.iam.repository.PolicyRepository;
import dev.cloudlite.iam.repository.RolePolicyRepository;
import dev.cloudlite.iam.repository.UserPolicyRepository;
import dev.cloudlite.iam.repository.UserRoleRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthorizationServiceTest {

    private UserRoleRepository userRoles;
    private UserPolicyRepository userPolicies;
    private RolePolicyRepository rolePolicies;
    private PolicyRepository policies;
    private PolicyService policyService;
    private AuthorizationService service;

    @BeforeEach
    void setUp() {
        userRoles = mock(UserRoleRepository.class);
        userPolicies = mock(UserPolicyRepository.class);
        rolePolicies = mock(RolePolicyRepository.class);
        policies = mock(PolicyRepository.class);
        policyService = mock(PolicyService.class);
        service = new AuthorizationService(userRoles, userPolicies, rolePolicies, policies, policyService);
    }

    @Test
    void authorizeAllowsWhenADirectlyAttachedPolicyGrantsTheAction() {
        UUID userId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        Policy policy = new Policy("read-only", "{}");
        when(userPolicies.findByIdUserId(userId)).thenReturn(List.of(new UserPolicy(userId, policyId)));
        when(userRoles.findByIdUserId(userId)).thenReturn(List.of());
        when(policies.findById(policyId)).thenReturn(Optional.of(policy));
        when(policyService.parseDocument(policy)).thenReturn(new PolicyDocument(List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:GetObject"), List.of("arn:cloudlite:s3:::b/*")))));

        Decision decision = service.authorize(userId, "s3:GetObject", "arn:cloudlite:s3:::b/key.txt");

        assertThat(decision).isEqualTo(Decision.ALLOW);
    }

    @Test
    void authorizeAllowsWhenARolePolicyGrantsTheAction() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        Policy policy = new Policy("developers-policy", "{}");
        when(userPolicies.findByIdUserId(userId)).thenReturn(List.of());
        when(userRoles.findByIdUserId(userId)).thenReturn(List.of(new UserRole(userId, roleId)));
        when(rolePolicies.findByIdRoleId(roleId)).thenReturn(List.of(new RolePolicy(roleId, policyId)));
        when(policies.findById(policyId)).thenReturn(Optional.of(policy));
        when(policyService.parseDocument(policy)).thenReturn(new PolicyDocument(List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:*"), List.of("arn:cloudlite:s3:::b/*")))));

        Decision decision = service.authorize(userId, "s3:PutObject", "arn:cloudlite:s3:::b/key.txt");

        assertThat(decision).isEqualTo(Decision.ALLOW);
    }

    @Test
    void authorizeDeniesWhenTheUserHasNoAttachedPolicies() {
        UUID userId = UUID.randomUUID();
        when(userPolicies.findByIdUserId(userId)).thenReturn(List.of());
        when(userRoles.findByIdUserId(userId)).thenReturn(List.of());

        Decision decision = service.authorize(userId, "s3:GetObject", "arn:cloudlite:s3:::b/key.txt");

        assertThat(decision).isEqualTo(Decision.DENY);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd services/iam && mvn -q test -Dtest=AuthorizationServiceTest`
Expected: PASS

- [ ] **Step 7: Create the DTOs and controllers**

`services/iam/src/main/java/dev/cloudlite/iam/dto/TokenResponse.java`:

```java
package dev.cloudlite.iam.dto;

public record TokenResponse(String token, long expiresIn) {
}
```

`services/iam/src/main/java/dev/cloudlite/iam/dto/AuthorizeRequest.java`:

```java
package dev.cloudlite.iam.dto;

public record AuthorizeRequest(String action, String resource) {
}
```

`services/iam/src/main/java/dev/cloudlite/iam/dto/AuthorizeResponse.java`:

```java
package dev.cloudlite.iam.dto;

public record AuthorizeResponse(String decision) {
}
```

`services/iam/src/main/java/dev/cloudlite/iam/controller/AuthController.java`:

```java
package dev.cloudlite.iam.controller;

import dev.cloudlite.iam.dto.TokenResponse;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.service.AuthService;
import dev.cloudlite.iam.service.TokenResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private static final String API_KEY_PREFIX = "ApiKey ";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/token")
    public ResponseEntity<TokenResponse> issueToken(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith(API_KEY_PREFIX)) {
            throw new IamApiException(IamErrorCode.INVALID_API_KEY);
        }
        String rawApiKey = authorization.substring(API_KEY_PREFIX.length());
        TokenResult result = authService.issueToken(rawApiKey);
        return ResponseEntity.ok(new TokenResponse(result.token(), result.expiresInSeconds()));
    }
}
```

`services/iam/src/main/java/dev/cloudlite/iam/controller/AuthorizationController.java`:

```java
package dev.cloudlite.iam.controller;

import dev.cloudlite.iam.dto.AuthorizeRequest;
import dev.cloudlite.iam.dto.AuthorizeResponse;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.policy.Decision;
import dev.cloudlite.iam.service.AuthService;
import dev.cloudlite.iam.service.AuthorizationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthorizationController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final AuthorizationService authorizationService;

    public AuthorizationController(AuthService authService, AuthorizationService authorizationService) {
        this.authService = authService;
        this.authorizationService = authorizationService;
    }

    @PostMapping("/authorize")
    public ResponseEntity<AuthorizeResponse> authorize(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody AuthorizeRequest request) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new IamApiException(IamErrorCode.TOKEN_INVALID);
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        UUID userId = authService.parseUserId(token);
        Decision decision = authorizationService.authorize(userId, request.action(), request.resource());
        return ResponseEntity.ok(new AuthorizeResponse(decision.name()));
    }
}
```

- [ ] **Step 8: Write the failing tests for `AuthController` and `AuthorizationController`**

`services/iam/src/test/java/dev/cloudlite/iam/controller/AuthControllerTest.java`:

```java
package dev.cloudlite.iam.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.iam.error.GlobalExceptionHandler;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.service.AuthService;
import dev.cloudlite.iam.service.TokenResult;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Test
    void issueTokenReturns200WithATokenBody() throws Exception {
        given(authService.issueToken("raw-key")).willReturn(new TokenResult("signed-jwt", 900));

        mockMvc.perform(post("/auth/token").header("Authorization", "ApiKey raw-key"))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("signed-jwt")));
    }

    @Test
    void issueTokenReturns401WhenTheApiKeyIsInvalid() throws Exception {
        given(authService.issueToken("bad-key")).willThrow(new IamApiException(IamErrorCode.INVALID_API_KEY));

        mockMvc.perform(post("/auth/token").header("Authorization", "ApiKey bad-key"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void issueTokenReturns401WhenTheAuthorizationHeaderIsMissingTheApiKeyPrefix() throws Exception {
        mockMvc.perform(post("/auth/token").header("Authorization", "Bearer something"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void issueTokenReturns401WhenTheAuthorizationHeaderIsAbsentEntirely() throws Exception {
        mockMvc.perform(post("/auth/token")).andExpect(status().isUnauthorized());
    }
}
```

`services/iam/src/test/java/dev/cloudlite/iam/controller/AuthorizationControllerTest.java`:

```java
package dev.cloudlite.iam.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.iam.error.GlobalExceptionHandler;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.policy.Decision;
import dev.cloudlite.iam.service.AuthService;
import dev.cloudlite.iam.service.AuthorizationService;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthorizationController.class)
@Import(GlobalExceptionHandler.class)
class AuthorizationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private AuthorizationService authorizationService;

    @Test
    void authorizeReturns200WithAllowWhenThePolicyEngineAllows() throws Exception {
        UUID userId = UUID.randomUUID();
        given(authService.parseUserId("good-jwt")).willReturn(userId);
        given(authorizationService.authorize(userId, "s3:GetObject", "arn:cloudlite:s3:::b/key"))
            .willReturn(Decision.ALLOW);

        mockMvc.perform(post("/authorize")
                .header("Authorization", "Bearer good-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"s3:GetObject\",\"resource\":\"arn:cloudlite:s3:::b/key\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("ALLOW")));
    }

    @Test
    void authorizeReturns401WhenTheTokenIsExpired() throws Exception {
        given(authService.parseUserId("expired-jwt")).willThrow(new IamApiException(IamErrorCode.TOKEN_EXPIRED));

        mockMvc.perform(post("/authorize")
                .header("Authorization", "Bearer expired-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"s3:GetObject\",\"resource\":\"arn:cloudlite:s3:::b/key\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authorizeReturns401WhenTheAuthorizationHeaderIsMissingTheBearerPrefix() throws Exception {
        mockMvc.perform(post("/authorize")
                .header("Authorization", "ApiKey something")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"s3:GetObject\",\"resource\":\"arn:cloudlite:s3:::b/key\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authorizeReturns401WhenTheAuthorizationHeaderIsAbsentEntirely() throws Exception {
        mockMvc.perform(post("/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"s3:GetObject\",\"resource\":\"arn:cloudlite:s3:::b/key\"}"))
            .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd services/iam && mvn -q test -Dtest=AuthControllerTest,AuthorizationControllerTest`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add services/iam/src/main/java/dev/cloudlite/iam/service/TokenResult.java \
  services/iam/src/main/java/dev/cloudlite/iam/service/AuthService.java \
  services/iam/src/main/java/dev/cloudlite/iam/service/AuthorizationService.java \
  services/iam/src/main/java/dev/cloudlite/iam/dto/TokenResponse.java \
  services/iam/src/main/java/dev/cloudlite/iam/dto/AuthorizeRequest.java \
  services/iam/src/main/java/dev/cloudlite/iam/dto/AuthorizeResponse.java \
  services/iam/src/main/java/dev/cloudlite/iam/controller/AuthController.java \
  services/iam/src/main/java/dev/cloudlite/iam/controller/AuthorizationController.java \
  services/iam/src/test/java/dev/cloudlite/iam/service/AuthServiceTest.java \
  services/iam/src/test/java/dev/cloudlite/iam/service/AuthorizationServiceTest.java \
  services/iam/src/test/java/dev/cloudlite/iam/controller/AuthControllerTest.java \
  services/iam/src/test/java/dev/cloudlite/iam/controller/AuthorizationControllerTest.java
git commit -m "feat: add API-key-to-JWT auth flow and the /authorize decision endpoint"
```

---

## Task 8: `docker-compose.yml` + `Dockerfile` for local dev

**Files:**
- Create: `services/iam/Dockerfile`
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: nothing from earlier tasks (build/deploy plumbing only, no Java code).
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Create `services/iam/Dockerfile`**

```
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /src/target/iam-0.1.0.jar /app/iam.jar
ENTRYPOINT ["java", "-jar", "/app/iam.jar"]
```

- [ ] **Step 2: Add the `iam` service to `docker-compose.yml`**

The full file becomes:

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

  iam:
    build:
      context: ./services/iam
    environment:
      SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres:5432/cloudlite"
      SPRING_DATASOURCE_USERNAME: "cloudlite"
      SPRING_DATASOURCE_PASSWORD: "cloudlite"
      IAM_JWT_SECRET: "dev-only-insecure-jwt-signing-secret-please-change"
      IAM_JWT_EXPIRY_SECONDS: "900"
      SERVER_PORT: "8081"
      JAVA_TOOL_OPTIONS: "-Xmx768m"
    ports:
      - "8081:8081"
    depends_on:
      postgres:
        condition: service_healthy

volumes:
  postgres-data:
  s3-data:
```

Note: `iam` shares the same `cloudlite` Postgres database as `s3` — both services' Flyway migrations run against the same schema namespace, but their table names don't collide (`buckets`/`objects` vs `users`/`roles`/`policies`/`user_roles`/`user_policies`/`role_policies`), matching `docs/services/iam.md`'s "same instance as S3's metadata index." `iam` has no volume of its own — unlike `s3`, it writes nothing to local disk.

- [ ] **Step 3: Verify the compose file builds**

Run: `docker compose build iam`
Expected: image builds successfully (this only validates the Dockerfile/Maven build, not runtime — no test framework step here, this task has no Java code).

- [ ] **Step 4: Commit**

```bash
git add services/iam/Dockerfile docker-compose.yml
git commit -m "build: add docker-compose service and Dockerfile for iam"
```

---

## Task 9: End-to-end integration test + `docs/services/iam.md` update

**Files:**
- Test: `services/iam/src/test/java/dev/cloudlite/iam/IamApplicationIntegrationTest.java`
- Modify: `docs/services/iam.md`

**Interfaces:**
- Consumes: the full stack from Tasks 1–7 (`/healthz`, `/users`, `/policies`, `/auth/token`, `/authorize`, and their DTOs).
- Produces: nothing later tasks depend on — this is the last task in the plan.

- [ ] **Step 1: Write the integration test**

`services/iam/src/test/java/dev/cloudlite/iam/IamApplicationIntegrationTest.java`:

```java
package dev.cloudlite.iam;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cloudlite.iam.dto.AuthorizeResponse;
import dev.cloudlite.iam.dto.CreatePolicyRequest;
import dev.cloudlite.iam.dto.CreatedUserResponse;
import dev.cloudlite.iam.dto.PolicyResponse;
import dev.cloudlite.iam.dto.TokenResponse;
import dev.cloudlite.iam.policy.Effect;
import dev.cloudlite.iam.policy.PolicyDocument;
import dev.cloudlite.iam.policy.PolicyStatement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamApplicationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthzReturns200OnceTheAppIsUp() {
        ResponseEntity<Void> response = restTemplate.getForEntity("/healthz", Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void createUserAttachPolicyIssueTokenThenAuthorizeAllowAndDeny() {
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<CreatedUserResponse> createUser = restTemplate.postForEntity(
            "/users", new HttpEntity<>("{\"username\":\"e2e-alice\"}", jsonHeaders), CreatedUserResponse.class);
        assertThat(createUser.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String apiKey = createUser.getBody().apiKey();

        PolicyDocument document = new PolicyDocument(List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:GetObject"), List.of("arn:cloudlite:s3:::e2e-bucket/*")),
            new PolicyStatement(Effect.DENY, List.of("s3:DeleteObject"), List.of("arn:cloudlite:s3:::e2e-bucket/*"))));
        CreatePolicyRequest createPolicyRequest = new CreatePolicyRequest("e2e-read-only", document);
        ResponseEntity<PolicyResponse> createPolicy = restTemplate.postForEntity(
            "/policies", new HttpEntity<>(createPolicyRequest, jsonHeaders), PolicyResponse.class);
        assertThat(createPolicy.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        restTemplate.postForEntity(
            "/users/" + createUser.getBody().id() + "/policies/" + createPolicy.getBody().id(), null, Void.class);

        HttpHeaders apiKeyHeaders = new HttpHeaders();
        apiKeyHeaders.set("Authorization", "ApiKey " + apiKey);
        ResponseEntity<TokenResponse> tokenResponse = restTemplate.postForEntity(
            "/auth/token", new HttpEntity<>(null, apiKeyHeaders), TokenResponse.class);
        assertThat(tokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String jwt = tokenResponse.getBody().token();

        HttpHeaders bearerHeaders = new HttpHeaders();
        bearerHeaders.set("Authorization", "Bearer " + jwt);
        bearerHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<AuthorizeResponse> allowResponse = restTemplate.postForEntity(
            "/authorize",
            new HttpEntity<>(
                "{\"action\":\"s3:GetObject\",\"resource\":\"arn:cloudlite:s3:::e2e-bucket/file.txt\"}", bearerHeaders),
            AuthorizeResponse.class);
        assertThat(allowResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowResponse.getBody().decision()).isEqualTo("ALLOW");

        ResponseEntity<AuthorizeResponse> denyResponse = restTemplate.postForEntity(
            "/authorize",
            new HttpEntity<>(
                "{\"action\":\"s3:DeleteObject\",\"resource\":\"arn:cloudlite:s3:::e2e-bucket/file.txt\"}", bearerHeaders),
            AuthorizeResponse.class);
        assertThat(denyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(denyResponse.getBody().decision()).isEqualTo("DENY");

        ResponseEntity<AuthorizeResponse> implicitDenyResponse = restTemplate.postForEntity(
            "/authorize",
            new HttpEntity<>(
                "{\"action\":\"s3:PutObject\",\"resource\":\"arn:cloudlite:s3:::other-bucket/file.txt\"}", bearerHeaders),
            AuthorizeResponse.class);
        assertThat(implicitDenyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(implicitDenyResponse.getBody().decision()).isEqualTo("DENY");
    }
}
```

- [ ] **Step 2: Run the full test suite**

Run: `cd services/iam && mvn -q test`
Expected: PASS — every test from Tasks 1–9, including this new integration test (requires Docker for Testcontainers).

- [ ] **Step 3: Update `docs/services/iam.md`**

Replace the entire file with:

```markdown
# IAM clone

**Status:** Phase 2 (standalone) built — user/role/policy CRUD, a
deny-overrides-allow policy evaluation engine, and an API-key-to-JWT
auth flow with a JWT-gated `/authorize` decision endpoint. Not yet
wired into S3 — that's a later build-order step (`architecture.md`
§11, step 3) with its own future spec/plan. See
[`../superpowers/plans/2026-08-21-iam-clone-phase2.md`](../superpowers/plans/2026-08-21-iam-clone-phase2.md)
for what was built and
[`../superpowers/specs/2026-08-21-iam-clone-phase2-design.md`](../superpowers/specs/2026-08-21-iam-clone-phase2-design.md)
for the design.

## Scope

- Users, roles, and policies with attached JSON policy documents
  (`Effect`/`Action`/`Resource`-shaped statements)
- Roles are policy bundles: a user can be a member of zero or more
  roles (static membership) and/or have policies attached directly —
  no assume-role/session semantics
- Policy evaluation engine (`policy/` package, framework-free,
  unit-tested in isolation) — deny-overrides-allow, exact or
  trailing-`*`-wildcard match on actions/resources, implicit
  default-deny
- Auth: `POST /auth/token` exchanges a user's API key for a
  short-lived signed JWT; `POST /authorize` verifies that JWT and runs
  the policy engine against a caller-supplied `(action, resource)`
  pair — this is the exact surface S3 will call once wired in

## Tech stack

- Java 21, Spring Boot (Spring MVC + virtual threads), Maven
- Spring Data JPA + Hibernate over PostgreSQL, Flyway migrations
- jjwt for JWT signing/verification (HMAC-SHA256)
- Plain JSON REST — no AWS wire-compatibility requirement (unlike S3)

## Storage

- Users/roles/policies: PostgreSQL (see
  [`../decisions/0005-postgresql-database.md`](../decisions/0005-postgresql-database.md)),
  same instance as S3's metadata index — table names don't collide
- Policy documents stored as `jsonb`, mapped via Hibernate's native
  JSON column support (no extra Hibernate-types dependency)

## Dependencies

- None yet. Will be consumed by the S3 service via a dedicated
  `iamclient` package once wired in (see [`s3.md`](s3.md)) —
  `architecture.md` §11, step 3.

## Build/test notes

Per `architecture.md` §11: the policy engine (deny-overrides-allow
logic) is unit-tested in isolation, with zero Spring/Jakarta
dependencies, ahead of any real caller existing. `/healthz` is
exposed from the first commit. Admin CRUD endpoints (`/users`,
`/roles`, `/policies`, and their attachment endpoints) are open — no
auth — in this phase, mirroring S3 Phase 1's own bootstrap posture;
only `/auth/token` and `/authorize` are auth-gated.

## Out of scope

See `../future-work.md` — cross-account roles/assume-role chains,
MFA, SSO/federation, and fine-grained condition keys (IP
restrictions, time-based conditions) are explicitly not part of this
service, at any phase.
```

- [ ] **Step 4: Commit**

```bash
git add services/iam/src/test/java/dev/cloudlite/iam/IamApplicationIntegrationTest.java docs/services/iam.md
git commit -m "test: add end-to-end integration test, update iam service doc"
```

