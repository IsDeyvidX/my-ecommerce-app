package com.example.ecommerce.service;

import com.example.ecommerce.model.User;
import com.example.ecommerce.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserServiceTest {
    private final UserService service = new UserService(new InMemoryUserRepository());

    @Test
    void registerUserRejectsNullUser() {
        assertThrows(IllegalArgumentException.class, () -> service.registerUser(null));
    }

    @Test
    void authenticateRejectsMissingCredentials() {
        assertFalse(service.authenticate(null, "senha123").isPresent());
        assertFalse(service.authenticate("ana@example.com", null).isPresent());
    }

    private static final class InMemoryUserRepository implements UserRepository {
        @Override
        public List<User> findAll() {
            return List.of();
        }

        @Override
        public Optional<User> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public Optional<User> findByEmail(String email) {
            return Optional.empty();
        }

        @Override
        public boolean existsByCpf(String cpf) {
            return false;
        }

        @Override
        public void updatePassword(Long id, String passwordHash) {
        }

        @Override
        public User save(User user) {
            return user;
        }

        @Override
        public void deleteById(Long id) {
        }
    }
}
