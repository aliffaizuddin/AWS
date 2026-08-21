package dev.cloudlite.s3.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cloudlite.s3.domain.Bucket;
import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.domain.ObjectMetadataId;
import java.util.UUID;
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
