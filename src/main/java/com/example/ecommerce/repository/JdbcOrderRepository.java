package com.example.ecommerce.repository;

import com.example.ecommerce.model.Product;
import com.example.ecommerce.persistence.Database;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class JdbcOrderRepository {
    public record CreatedOrder(long id, BigDecimal total) {
    }

    public record OrderSummary(long id, BigDecimal total, String paymentMethod,
                               String deliveryAddress, String createdAt) {
    }

    private record OrderLine(Product product, int quantity, BigDecimal unitPrice) {
    }

    public CreatedOrder create(Long userId, Map<Long, Integer> quantities,
                               String paymentMethod, String deliveryAddress) {
        if (quantities == null || quantities.isEmpty() || quantities.size() > 50) {
            throw new IllegalArgumentException("A sacola precisa ter entre 1 e 50 produtos diferentes.");
        }
        if (!"PIX".equals(paymentMethod) && !"DINHEIRO".equals(paymentMethod)
                && !"CARTAO".equals(paymentMethod)) {
            throw new IllegalArgumentException("Selecione uma forma de pagamento valida.");
        }
        if (deliveryAddress == null || deliveryAddress.isBlank() || deliveryAddress.length() > 500) {
            throw new IllegalArgumentException("Informe um endereco de entrega com ate 500 caracteres.");
        }
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || entry.getKey() <= 0 || entry.getValue() < 1 || entry.getValue() > 99) {
                throw new IllegalArgumentException("A sacola contem um produto ou quantidade invalida.");
            }
        }

        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<OrderLine> lines = loadOrderLines(connection, quantities);
                BigDecimal total = lines.stream()
                        .map(line -> line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .setScale(2, RoundingMode.HALF_UP);
                long orderId = insertOrder(connection, userId, total, paymentMethod, deliveryAddress.trim());
                for (OrderLine line : lines) {
                    decrementStock(connection, line.product().getId(), line.quantity());
                    insertOrderLine(connection, orderId, line);
                }
                connection.commit();
                return new CreatedOrder(orderId, total);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Could not create the order.", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create the order.", exception);
        }
    }

    public List<OrderSummary> findByUserId(Long userId) {
        String sql = """
                SELECT id, total, payment_method, delivery_address, created_at
                FROM orders
                WHERE user_id = ?
                ORDER BY created_at DESC, id DESC
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet results = statement.executeQuery()) {
                List<OrderSummary> orders = new ArrayList<>();
                while (results.next()) {
                    Timestamp createdAt = results.getTimestamp("created_at");
                    orders.add(new OrderSummary(
                            results.getLong("id"),
                            results.getBigDecimal("total"),
                            results.getString("payment_method"),
                            results.getString("delivery_address"),
                            createdAt.toInstant().toString()));
                }
                return orders;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not retrieve order history.", exception);
        }
    }

    private List<OrderLine> loadOrderLines(Connection connection, Map<Long, Integer> quantities)
            throws SQLException {
        List<OrderLine> lines = new ArrayList<>();
        String sql = "SELECT id, name, description, price, stock FROM products WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<Long, Integer> entry : quantities.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .toList()) {
                statement.setLong(1, entry.getKey());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalArgumentException("Um dos produtos da sacola nao existe mais.");
                    }
                    Product product = new Product(
                            result.getLong("id"),
                            result.getString("name"),
                            result.getString("description"),
                            result.getDouble("price"),
                            result.getInt("stock"));
                    if (product.getStock() < entry.getValue()) {
                        throw new IllegalArgumentException(
                                "Estoque insuficiente para o produto " + product.getName() + ".");
                    }
                    lines.add(new OrderLine(
                            product,
                            entry.getValue(),
                            result.getBigDecimal("price").setScale(2, RoundingMode.HALF_UP)));
                }
            }
        }
        return lines;
    }

    private long insertOrder(Connection connection, Long userId, BigDecimal total,
                             String paymentMethod, String deliveryAddress) throws SQLException {
        String sql = "INSERT INTO orders (user_id, total, payment_method, delivery_address) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setBigDecimal(2, total);
            statement.setString(3, paymentMethod);
            statement.setString(4, deliveryAddress);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The database did not return an order ID.");
                }
                return keys.getLong(1);
            }
        }
    }

    private void decrementStock(Connection connection, Long productId, int quantity) throws SQLException {
        String sql = "UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, quantity);
            statement.setLong(2, productId);
            statement.setInt(3, quantity);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("O estoque mudou; atualize a sacola e tente novamente.");
            }
        }
    }

    private void insertOrderLine(Connection connection, long orderId, OrderLine line) throws SQLException {
        String sql = "INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, orderId);
            statement.setLong(2, line.product().getId());
            statement.setInt(3, line.quantity());
            statement.setBigDecimal(4, line.unitPrice());
            statement.executeUpdate();
        }
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
