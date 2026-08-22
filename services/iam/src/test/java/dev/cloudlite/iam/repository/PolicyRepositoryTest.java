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
