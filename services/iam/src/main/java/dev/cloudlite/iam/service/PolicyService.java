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
