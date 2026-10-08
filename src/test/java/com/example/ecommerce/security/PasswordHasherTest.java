package com.example.ecommerce.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    @Test
    void verifyRejectsNullCredentials() {
        assertFalse(PasswordHasher.verify(null, "pbkdf2-sha256$310000$abc$def"));
        assertFalse(PasswordHasher.verify("senha123", null));
    }

    @Test
    void verifyValidPlainTextLegacyPassword() {
        assertTrue(PasswordHasher.verify("senha123", "senha123"));
    }

    @Test
    void hashRejectsNullPassword() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PasswordHasher.hash(null));
    }
}
