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
