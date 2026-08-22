package dev.cloudlite.iam.service;

import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.repository.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository users;
    private final SecretKey signingKey;
    private final long expirySeconds;

    public AuthService(
            UserRepository users,
            @Value("${iam.jwt.secret}") String secret,
            @Value("${iam.jwt.expiry-seconds}") long expirySeconds) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("iam.jwt.secret must be at least 32 bytes");
        }
        this.users = users;
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirySeconds = expirySeconds;
    }

    public TokenResult issueToken(String rawApiKey) {
        String hash = ApiKeyGenerator.hash(rawApiKey);
        var user = users.findByApiKeyHash(hash)
            .orElseThrow(() -> new IamApiException(IamErrorCode.INVALID_API_KEY));

        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirySeconds * 1000);
        String token = Jwts.builder()
            .subject(user.getId().toString())
            .issuedAt(now)
            .expiration(expiry)
            .signWith(signingKey)
            .compact();

        return new TokenResult(token, expirySeconds);
    }

    public UUID parseUserId(String token) {
        try {
            String subject = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
            return UUID.fromString(subject);
        } catch (ExpiredJwtException e) {
            throw new IamApiException(IamErrorCode.TOKEN_EXPIRED);
        } catch (JwtException e) {
            throw new IamApiException(IamErrorCode.TOKEN_INVALID);
        }
    }
}
