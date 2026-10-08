package com.example.ecommerce.repository;

import com.example.ecommerce.model.User;
import com.example.ecommerce.persistence.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcUserRepository implements UserRepository {
    @Override
    public List<User> findAll() {
        String sql = "SELECT id, username, email, password, cpf, role FROM users ORDER BY id";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            List<User> users = new ArrayList<>();
            while (results.next()) {
                users.add(mapUser(results));
            }
            return users;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not retrieve users from the database.", exception);
        }
    }

    @Override
    public Optional<User> findById(Long id) {
        String sql = "SELECT id, username, email, password, cpf, role FROM users WHERE id = ?";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(mapUser(results)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not retrieve user " + id + " from the database.", exception);
        }
    }

    @Override
    public Optional<User> findByEmail(String email) {
        String sql = "SELECT id, username, email, password, cpf, role FROM users WHERE email = ?";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(mapUser(results)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not retrieve account by email.", exception);
        }
    }

    @Override
    public boolean existsByCpf(String cpf) {
        String sql = "SELECT 1 FROM users WHERE cpf = ?";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cpf);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not verify the CPF.", exception);
        }
    }

    @Override
    public void updatePassword(Long id, String passwordHash) {
        try (Connection connection = Database.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement("UPDATE users SET password = ? WHERE id = ?")) {
            statement.setString(1, passwordHash);
            statement.setLong(2, id);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update account credentials.", exception);
        }
    }

    @Override
    public User save(User user) {
        if (user.getId() == null) {
            return insert(user);
        }

        String sql = "UPDATE users SET username = ?, email = ?, password = ?, cpf = ?, role = ? WHERE id = ?";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            setUserFields(statement, user);
            statement.setLong(6, user.getId());
            if (statement.executeUpdate() > 0) {
                return user;
            }
            return insertWithId(user);
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                throw new IllegalArgumentException("O email ou CPF informado ja esta cadastrado.");
            }
            throw new IllegalStateException("Could not save user to the database.", exception);
        }
    }

    @Override
    public void deleteById(Long id) {
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not delete user " + id + " from the database.", exception);
        }
    }

    private User insert(User user) {
        String sql = "INSERT INTO users (username, email, password, cpf, role) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setUserFields(statement, user);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The database did not return a user ID.");
                }
                user.setId(keys.getLong(1));
            }
            return user;
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                throw new IllegalArgumentException("O email ou CPF informado ja esta cadastrado.");
            }
            throw new IllegalStateException("Could not insert user into the database.", exception);
        }
    }

    private User insertWithId(User user) throws SQLException {
        String sql = "INSERT INTO users (id, username, email, password, cpf, role) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, user.getId());
            setUserFields(statement, user, 2);
            statement.executeUpdate();
            return user;
        }
    }

    private void setUserFields(PreparedStatement statement, User user) throws SQLException {
        setUserFields(statement, user, 1);
    }

    private void setUserFields(PreparedStatement statement, User user, int startIndex) throws SQLException {
        statement.setString(startIndex, user.getUsername());
        statement.setString(startIndex + 1, user.getEmail());
        statement.setString(startIndex + 2, user.getPassword());
        statement.setString(startIndex + 3, user.getCpf());
        statement.setString(startIndex + 4, user.getRole());
    }

    private User mapUser(ResultSet results) throws SQLException {
        return new User(
                results.getLong("id"),
                results.getString("username"),
                results.getString("email"),
                results.getString("password"),
                results.getString("cpf"),
                results.getString("role"));
    }
}
