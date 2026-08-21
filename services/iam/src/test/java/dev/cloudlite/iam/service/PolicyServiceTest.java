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
