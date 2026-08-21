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
