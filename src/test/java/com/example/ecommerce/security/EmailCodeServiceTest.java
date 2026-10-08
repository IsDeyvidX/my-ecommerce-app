package com.example.ecommerce.security;

import com.example.ecommerce.model.User;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailCodeServiceTest {
    @Test
    void verificationCodeIsSingleUseAndBoundToItsChallenge() {
        AtomicReference<String> sentCode = new AtomicReference<>();
        EmailCodeService service = new EmailCodeService((email, name, code) -> sentCode.set(code));
        String challengeId = service.sendCode(user(42));

        String incorrectCode = "000000".equals(sentCode.get()) ? "000001" : "000000";
        assertFalse(service.verify(challengeId, incorrectCode).isPresent());
        assertEquals(42, service.verify(challengeId, sentCode.get()).orElseThrow());
        assertTrue(service.verify(challengeId, sentCode.get()).isEmpty());
    }

    @Test
    void verificationCodeExpiresAfterFiveIncorrectAttempts() {
        AtomicReference<String> sentCode = new AtomicReference<>();
        EmailCodeService service = new EmailCodeService((email, name, code) -> sentCode.set(code));
        String challengeId = service.sendCode(user(7));

        for (int attempt = 0; attempt < 5; attempt++) {
            service.verify(challengeId, "not-a-code");
        }

        assertTrue(service.verify(challengeId, sentCode.get()).isEmpty());
    }

    @Test
    void userCannotRequestCodesMoreOftenThanOncePerMinute() {
        EmailCodeService service = new EmailCodeService((email, name, code) -> { });
        User user = user(9);
        service.sendCode(user);

        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                EmailCodeService.DeliveryException.class, () -> service.sendCode(user))
                .getMessage().contains("um minuto"));
    }

    private static User user(long id) {
        return new User(id, "Cliente", "cliente@example.com", "hashed-password");
    }
}
