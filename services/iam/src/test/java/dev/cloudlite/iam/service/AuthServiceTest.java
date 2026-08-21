package dev.cloudlite.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.cloudlite.iam.domain.User;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

    private static final String SECRET = "test-only-signing-secret-at-least-32-bytes-long";

    private UserRepository users;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        service = new AuthService(users, SECRET, 900);
    }

    @Test
    void constructorRejectsATooShortSecret() {
        assertThatThrownBy(() -> new AuthService(users, "too-short", 900))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void issueTokenRejectsAnUnknownApiKey() {
        when(users.findByApiKeyHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueToken("unknown-key"))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.INVALID_API_KEY);
    }

    @Test
    void issueTokenThenParseUserIdRoundTripsTheUserId() {
        User user = new User("alice", ApiKeyGenerator.hash("raw-key"));
        when(users.findByApiKeyHash(ApiKeyGenerator.hash("raw-key"))).thenReturn(Optional.of(user));

        TokenResult result = service.issueToken("raw-key");
        UUID parsed = service.parseUserId(result.token());

        assertThat(parsed).isEqualTo(user.getId());
        assertThat(result.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void parseUserIdRejectsAMalformedToken() {
        assertThatThrownBy(() -> service.parseUserId("not-a-real-jwt"))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.TOKEN_INVALID);
    }
}
