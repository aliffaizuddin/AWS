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
