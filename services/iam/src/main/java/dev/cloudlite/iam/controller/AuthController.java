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
