package com.example.ecommerce.repository;

import com.example.ecommerce.model.User;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryUserRepository implements UserRepository {
    private final Map<Long, User> users = new HashMap<>();

    @Override
    public List<User> findAll() {
        return new ArrayList<>(users.values());
    }

    @Override
    public Optional<User> findById(Long id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return users.values().stream().filter(user -> email.equalsIgnoreCase(user.getEmail())).findFirst();
    }

    @Override
    public boolean existsByCpf(String cpf) {
        return users.values().stream().anyMatch(user -> cpf.equals(user.getCpf()));
    }

    @Override
    public void updatePassword(Long id, String passwordHash) {
        User user = users.get(id);
        if (user != null) {
            user.setPassword(passwordHash);
        }
    }

    @Override
    public User save(User user) {
        if (user.getId() == null) {
            long nextId = users.keySet().stream().mapToLong(Long::longValue).max().orElse(0L) + 1L;
            user.setId(nextId);
        }
        users.put(user.getId(), user);
        return user;
    }

    @Override
    public void deleteById(Long id) {
        users.remove(id);
    }
}
