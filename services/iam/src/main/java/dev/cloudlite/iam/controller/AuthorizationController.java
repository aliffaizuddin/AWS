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
        if (request.action() == null || request.action().isBlank()
                || request.resource() == null || request.resource().isBlank()) {
            throw new IamApiException(IamErrorCode.INVALID_ARGUMENT);
        }
        Decision decision = authorizationService.authorize(userId, request.action(), request.resource());
        return ResponseEntity.ok(new AuthorizeResponse(decision.name()));
    }
}
