package dev.cloudlite.s3.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cloudlite.s3.domain.Bucket;
import java.util.List;
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
