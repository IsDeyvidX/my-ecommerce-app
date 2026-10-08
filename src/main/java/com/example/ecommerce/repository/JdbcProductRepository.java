package com.example.ecommerce.repository;

import com.example.ecommerce.model.Product;
import com.example.ecommerce.persistence.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcProductRepository implements ProductRepository {
    @Override
    public List<Product> findAll() {
        String sql = "SELECT id, name, description, price, stock, "
                + "CASE WHEN image_data IS NULL THEN NULL "
                + "ELSE '/api/products/' || id || '/image' END AS image_url "
                + "FROM products ORDER BY id";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            List<Product> products = new ArrayList<>();
            while (results.next()) {
                products.add(mapProduct(results, false));
            }
            return products;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not retrieve products from the database.", exception);
        }
    }

    @Override
    public Optional<Product> findById(Long id) {
        String sql = "SELECT id, name, description, price, stock, image_data, image_type, "
                + "CASE WHEN image_data IS NULL THEN NULL "
                + "ELSE '/api/products/' || id || '/image' END AS image_url FROM products WHERE id = ?";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(mapProduct(results, true)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not retrieve product " + id + " from the database.", exception);
        }
    }

    @Override
    public Product save(Product product) {
        if (product.getId() == null) {
            return insert(product);
        }

        String sql = "UPDATE products SET name = ?, description = ?, price = ?, stock = ?, "
                + "image_data = COALESCE(?, image_data), image_type = COALESCE(?, image_type) WHERE id = ?";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            setProductFields(statement, product);
            setImageFields(statement, product, 5);
            statement.setLong(7, product.getId());
            if (statement.executeUpdate() > 0) {
                setImageUrl(connection, product);
                return product;
            }
            return insertWithId(product);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save product to the database.", exception);
        }
    }

    @Override
    public void deleteById(Long id) {
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM products WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (SQLException exception) {
            if ("23503".equals(exception.getSQLState())) {
                throw new IllegalArgumentException(
                        "Este produto aparece em pedidos existentes e nao pode ser excluido.");
            }
            throw new IllegalStateException("Could not delete product " + id + " from the database.", exception);
        }
    }

    private Product insert(Product product) {
        String sql = "INSERT INTO products (name, description, price, stock, image_data, image_type) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setProductFields(statement, product);
            setImageFields(statement, product, 5);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The database did not return a product ID.");
                }
                product.setId(keys.getLong(1));
            }
            setImageUrl(connection, product);
            return product;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not insert product into the database.", exception);
        }
    }

    private Product insertWithId(Product product) throws SQLException {
        String sql = "INSERT INTO products (id, name, description, price, stock, image_data, image_type) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, product.getId());
            setProductFields(statement, product, 2);
            setImageFields(statement, product, 6);
            statement.executeUpdate();
            return product;
        }
    }

    private void setProductFields(PreparedStatement statement, Product product) throws SQLException {
        setProductFields(statement, product, 1);
    }

    private void setProductFields(PreparedStatement statement, Product product, int startIndex) throws SQLException {
        statement.setString(startIndex, product.getName());
        statement.setString(startIndex + 1, product.getDescription());
        statement.setDouble(startIndex + 2, product.getPrice());
        statement.setInt(startIndex + 3, product.getStock());
    }

    private void setImageFields(PreparedStatement statement, Product product, int startIndex) throws SQLException {
        if (product.getImageData() == null) {
            statement.setNull(startIndex, Types.BLOB);
            statement.setNull(startIndex + 1, Types.VARCHAR);
            return;
        }
        statement.setBytes(startIndex, product.getImageData());
        statement.setString(startIndex + 1, product.getImageType());
    }

    private void setImageUrl(Connection connection, Product product) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT image_data IS NOT NULL FROM products WHERE id = ?")) {
            statement.setLong(1, product.getId());
            try (ResultSet results = statement.executeQuery()) {
                product.setImageUrl(results.next() && results.getBoolean(1)
                        ? "/api/products/" + product.getId() + "/image" : null);
            }
        }
    }

    private Product mapProduct(ResultSet results, boolean includeImageData) throws SQLException {
        Product product = new Product(
                results.getLong("id"),
                results.getString("name"),
                results.getString("description"),
                results.getDouble("price"),
                results.getInt("stock"));
        product.setImageUrl(results.getString("image_url"));
        if (includeImageData) {
            product.setImageData(results.getBytes("image_data"));
            product.setImageType(results.getString("image_type"));
        }
        return product;
    }
}
