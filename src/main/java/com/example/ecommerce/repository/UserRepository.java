package com.example.ecommerce.repository;

import com.example.ecommerce.model.User;
import java.util.List;
import java.util.Optional;

public interface UserRepository {
    List<User> findAll();
    Optional<User> findById(Long id);
    Optional<User> findByEmail(String email);
    boolean existsByCpf(String cpf);
    void updatePassword(Long id, String passwordHash);
    User save(User user);
    void deleteById(Long id);
}