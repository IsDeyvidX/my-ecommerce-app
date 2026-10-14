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
    public record OrderItemSummary(String name, int quantity, BigDecimal unitPrice, BigDecimal subtotal) {
    }

    public record CreatedOrder(long id, BigDecimal total, List<OrderItemSummary> items) {
    }

    public record OrderSummary(long id, BigDecimal total, String paymentMethod,
                               String deliveryAddress, String customerPhone, Boolean cashNeedsChange,
                               BigDecimal cashAmount, BigDecimal changeDue, String cardType,
                               String createdAt) {
    }

    public record AdminOrderSummary(long id, String customerName, String customerEmail,
                                    String customerPhone, BigDecimal total, String paymentMethod,
                                    String deliveryAddress, Boolean cashNeedsChange, BigDecimal cashAmount,
                                    BigDecimal changeDue, String cardType, String createdAt,
                                    boolean notificationPending, List<OrderItemSummary> items) {
    }

    private record OrderLine(Product product, int quantity, BigDecimal unitPrice) {
    }

    public CreatedOrder create(Long userId, Map<Long, Integer> quantities,
                               String paymentMethod, String deliveryAddress) {
        return create(userId, quantities, paymentMethod, deliveryAddress, null, null, null, null);
    }

    public CreatedOrder create(Long userId, Map<Long, Integer> quantities,
                               String paymentMethod, String deliveryAddress,
                               String customerPhone, Boolean cashNeedsChange, String cardType,
                               BigDecimal cashAmount) {
        if (quantities == null || quantities.isEmpty() || quantities.size() > 50) {
            throw new IllegalArgumentException("A sacola precisa ter entre 1 e 50 produtos diferentes.");
        }
        if (!"PIX".equals(paymentMethod) && !"DINHEIRO".equals(paymentMethod)
                && !"CARTAO".equals(paymentMethod)) {
            throw new IllegalArgumentException("Selecione uma forma de pagamento valida.");
        }
        boolean invalidPaymentDetails = switch (paymentMethod) {
            case "DINHEIRO" -> cashNeedsChange == null || cardType != null
                    || Boolean.TRUE.equals(cashNeedsChange) != (cashAmount != null);
            case "CARTAO" -> cashNeedsChange != null
                    || cashAmount != null
                    || !"DEBITO".equals(cardType) && !"CREDITO".equals(cardType);
            default -> cashNeedsChange != null || cardType != null || cashAmount != null;
        };
        if (invalidPaymentDetails) {
            throw new IllegalArgumentException("Informe os dados obrigatorios da forma de pagamento escolhida.");
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
                BigDecimal changeDue = null;
                if (cashAmount != null) {
                    if (cashAmount.scale() > 2 || cashAmount.signum() <= 0) {
                        throw new IllegalArgumentException("Informe um valor em dinheiro valido.");
                    }
                    if (cashAmount.compareTo(total) < 0) {
                        throw new IllegalArgumentException("O valor informado precisa cobrir o total do pedido.");
                    }
                    changeDue = cashAmount.subtract(total).setScale(2, RoundingMode.UNNECESSARY);
                }
                long orderId = insertOrder(connection, userId, total, paymentMethod, deliveryAddress.trim(),
                        customerPhone, cashNeedsChange, cashAmount, changeDue, cardType);
                for (OrderLine line : lines) {
                    decrementStock(connection, line.product().getId(), line.quantity());
                    insertOrderLine(connection, orderId, line);
                }
                connection.commit();
                List<OrderItemSummary> orderItems = lines.stream()
                        .map(line -> new OrderItemSummary(line.product().getName(), line.quantity(),
                                line.unitPrice(), line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()))
                                .setScale(2, RoundingMode.HALF_UP)))
                        .toList();
                return new CreatedOrder(orderId, total, orderItems);
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
                SELECT id, total, payment_method, delivery_address, customer_phone,
                       cash_needs_change, cash_amount, change_due, card_type, created_at
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
                            results.getString("customer_phone"),
                            (Boolean) results.getObject("cash_needs_change"),
                            results.getBigDecimal("cash_amount"),
                            results.getBigDecimal("change_due"),
                            results.getString("card_type"),
                            createdAt.toInstant().toString()));
                }
                return orders;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not retrieve order history.", exception);
        }
    }

    public List<AdminOrderSummary> findAllForAdmin() {
        String sql = """
                SELECT o.id, u.username, u.email, o.customer_phone, o.total, o.payment_method,
                       o.delivery_address, o.cash_needs_change, o.cash_amount, o.change_due,
                       o.card_type, o.created_at, o.admin_seen_at
                FROM orders o
                JOIN users u ON u.id = o.user_id
                ORDER BY o.created_at DESC, o.id DESC
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            List<AdminOrderSummary> orders = new ArrayList<>();
            while (results.next()) {
                long orderId = results.getLong("id");
                Timestamp createdAt = results.getTimestamp("created_at");
                orders.add(new AdminOrderSummary(
                        orderId,
                        results.getString("username"),
                        results.getString("email"),
                        results.getString("customer_phone"),
                        results.getBigDecimal("total"),
                        results.getString("payment_method"),
                        results.getString("delivery_address"),
                        (Boolean) results.getObject("cash_needs_change"),
                        results.getBigDecimal("cash_amount"),
                        results.getBigDecimal("change_due"),
                        results.getString("card_type"),
                        createdAt.toInstant().toString(),
                        results.getTimestamp("admin_seen_at") == null,
                        findItems(connection, orderId)));
            }
            return orders;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not retrieve admin order notifications.", exception);
        }
    }

    public long countPendingAdminNotifications() {
        String sql = "SELECT COUNT(*) FROM orders WHERE admin_seen_at IS NULL";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            results.next();
            return results.getLong(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not count admin order notifications.", exception);
        }
    }

    public void markAdminNotificationsSeen(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        if (orderIds.size() > 5000 || orderIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("A lista de pedidos a marcar como lidos e invalida.");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(orderIds.size(), "?"));
        String sql = "UPDATE orders SET admin_seen_at = CURRENT_TIMESTAMP "
                + "WHERE admin_seen_at IS NULL AND id IN (" + placeholders + ")";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < orderIds.size(); index++) {
                statement.setLong(index + 1, orderIds.get(index));
            }
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not mark admin order notifications as seen.", exception);
        }
    }

    private List<OrderItemSummary> findItems(Connection connection, long orderId) throws SQLException {
        String sql = """
                SELECT product_name, quantity, unit_price
                FROM order_items
                WHERE order_id = ?
                ORDER BY id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, orderId);
            try (ResultSet results = statement.executeQuery()) {
                List<OrderItemSummary> items = new ArrayList<>();
                while (results.next()) {
                    BigDecimal unitPrice = results.getBigDecimal("unit_price");
                    int quantity = results.getInt("quantity");
                    items.add(new OrderItemSummary(
                            results.getString("product_name"),
                            quantity,
                            unitPrice,
                            unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP)));
                }
                return items;
            }
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
                             String paymentMethod, String deliveryAddress,
                             String customerPhone, Boolean cashNeedsChange, BigDecimal cashAmount,
                             BigDecimal changeDue, String cardType) throws SQLException {
        String sql = "INSERT INTO orders "
                + "(user_id, total, payment_method, delivery_address, customer_phone, "
                + "cash_needs_change, cash_amount, change_due, card_type, admin_seen_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setBigDecimal(2, total);
            statement.setString(3, paymentMethod);
            statement.setString(4, deliveryAddress);
            statement.setString(5, customerPhone);
            if (cashNeedsChange == null) {
                statement.setNull(6, java.sql.Types.BOOLEAN);
            } else {
                statement.setBoolean(6, cashNeedsChange);
            }
            statement.setBigDecimal(7, cashAmount);
            statement.setBigDecimal(8, changeDue);
            statement.setString(9, cardType);
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
        String sql = "INSERT INTO order_items "
                + "(order_id, product_id, product_name, quantity, unit_price) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, orderId);
            statement.setLong(2, line.product().getId());
            statement.setString(3, line.product().getName());
            statement.setInt(4, line.quantity());
            statement.setBigDecimal(5, line.unitPrice());
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
