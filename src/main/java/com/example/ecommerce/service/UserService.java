package com.example.ecommerce.service;

import com.example.ecommerce.model.User;
import com.example.ecommerce.repository.UserRepository;
import com.example.ecommerce.security.PasswordHasher;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Base64;
import java.security.SecureRandom;

public class UserService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User registerUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("Dados do usuario invalidos.");
        }

        String email = user.getEmail() == null ? "" : user.getEmail().trim().toLowerCase(Locale.ROOT);
        String name = user.getUsername() == null ? "" : user.getUsername().trim();
        String cpfInput = user.getCpf() == null ? "" : user.getCpf().trim();
        if (!cpfInput.matches("[0-9.\\- ]+")) {
            throw new IllegalArgumentException("Informe um CPF valido.");
        }
        String cpf = cpfInput.replaceAll("\\D", "");
        String password = user.getPassword();

        if (name.length() < 2 || name.length() > 100) {
            throw new IllegalArgumentException("Informe um nome entre 2 e 100 caracteres.");
        }
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || email.length() > 255) {
            throw new IllegalArgumentException("Informe um email valido.");
        }
        if (!isValidCpf(cpf)) {
            throw new IllegalArgumentException("Informe um CPF valido.");
        }
        if (password == null || password.length() < 8 || password.length() > 200) {
            throw new IllegalArgumentException("A senha deve ter entre 8 e 200 caracteres.");
        }
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Ja existe uma conta com este email.");
        }
        if (userRepository.existsByCpf(cpf)) {
            throw new IllegalArgumentException("Ja existe uma conta com este CPF.");
        }

        user.setUsername(name);
        user.setEmail(email);
        user.setCpf(cpf);
        user.setPassword(PasswordHasher.hash(password));
        user.setRole("CUSTOMER");
        return userRepository.save(user);
    }

    public Optional<User> authenticate(String email, String password) {
        if (email == null || password == null || email.trim().isEmpty()) {
            return Optional.empty();
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (password.isEmpty()) {
            return Optional.empty();
        }
        Optional<User> result = userRepository.findByEmail(normalizedEmail);
        if (result.isEmpty() || !PasswordHasher.verify(password, result.get().getPassword())) {
            return Optional.empty();
        }
        User user = result.get();
        if (!PasswordHasher.isHashed(user.getPassword())) {
            user.setPassword(PasswordHasher.hash(password));
            userRepository.updatePassword(user.getId(), user.getPassword());
        }
        return Optional.of(user);
    }

    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User updateUser(User user) {
        return userRepository.save(user);
    }

    public void deleteUser(Long id) {
        userRepository.deleteById(id);
    }

    public void deleteAccount(Long id, String password) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Conta nao encontrada."));
        if (authenticate(user.getEmail(), password).filter(authenticated -> authenticated.getId().equals(id))
                .isEmpty()) {
            throw new IllegalArgumentException("A senha informada esta incorreta.");
        }
        anonymizeAccount(id);
    }

    public void anonymizeAccount(Long id) {
        if (id == null || userRepository.findById(id).isEmpty()) {
            throw new IllegalArgumentException("Conta nao encontrada.");
        }
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        userRepository.anonymizeById(id, "Conta excluida #" + id,
                "excluida-" + id + "@deleted.invalid",
                PasswordHasher.hash(Base64.getEncoder().encodeToString(randomBytes)));
    }

    private boolean isValidCpf(String cpf) {
        if (!cpf.matches("\\d{11}") || cpf.chars().distinct().count() == 1) {
            return false;
        }
        int firstDigit = cpfDigit(cpf, 9, 10);
        int secondDigit = cpfDigit(cpf, 10, 11);
        return cpf.charAt(9) - '0' == firstDigit && cpf.charAt(10) - '0' == secondDigit;
    }

    private int cpfDigit(String cpf, int length, int weightStart) {
        int sum = 0;
        for (int index = 0; index < length; index++) {
            sum += (cpf.charAt(index) - '0') * (weightStart - index);
        }
        int remainder = (sum * 10) % 11;
        return remainder == 10 ? 0 : remainder;
    }
}