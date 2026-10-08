package com.example.ecommerce.persistence;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

public final class Database {
    private static volatile String jdbcUrl = System.getenv().getOrDefault(
            "ECOMMERCE_JDBC_URL", "jdbc:h2:file:./db/ecommerce");
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    private Database() {
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
    }

    public static synchronized void initialize(String connectionUrl) {
        if (connectionUrl == null || connectionUrl.isBlank()) {
            throw new IllegalArgumentException("A URL de conexao com o banco e obrigatoria.");
        }
        jdbcUrl = connectionUrl;
        initialize();
    }

    public static void initialize() {
        try {
            Files.createDirectories(Path.of("db"));
            String schema = readSchema();
            try (Connection connection = getConnection();
                 Statement statement = connection.createStatement()) {
                for (String sql : schema.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Could not initialize the H2 database in ./db.", exception);
        }
    }

    private static String readSchema() throws IOException {
        InputStream input = Database.class.getResourceAsStream("/db/schema.sql");
        if (input == null) {
            throw new IOException("Database schema resource /db/schema.sql was not found.");
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }
    }
}
