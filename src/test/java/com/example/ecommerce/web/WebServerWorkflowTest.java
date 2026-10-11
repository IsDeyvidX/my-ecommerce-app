package com.example.ecommerce.web;

import com.example.ecommerce.model.User;
import com.example.ecommerce.persistence.Database;
import com.example.ecommerce.repository.JdbcOrderRepository;
import com.example.ecommerce.repository.JdbcProductRepository;
import com.example.ecommerce.repository.JdbcUserRepository;
import com.example.ecommerce.service.ProductService;
import com.example.ecommerce.service.UserService;
import com.example.ecommerce.util.DataSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebServerWorkflowTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void adminCanUploadProductsAndCustomersCannotAccessAdminArea() throws Exception {
        Database.initialize("jdbc:h2:mem:admin-workflow;DB_CLOSE_DELAY=-1");
        JdbcProductRepository productRepository = new JdbcProductRepository();
        JdbcUserRepository userRepository = new JdbcUserRepository();
        ProductService productService = new ProductService(productRepository);
        UserService userService = new UserService(userRepository);
        DataSeeder.seedProducts(productService);
        User admin = userService.registerUser(user("Admin", "admin@example.com", "529.982.247-25"));
        admin.setRole("ADMIN");
        userService.updateUser(admin);

        JdbcOrderRepository orderRepository = new JdbcOrderRepository();
        HttpServer server = WebServer.start(productService, userService, orderRepository, "0.0.0.0", 0);
        try {
            assertTrue(server.getAddress().getAddress().isAnyLocalAddress());
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            assertOriginPolicy(client, baseUrl);

            AuthenticatedResponse adminLogin = postAuthenticatedJson(client, baseUrl + "/api/auth/login",
                    "{\"email\":\"admin@example.com\",\"password\":\"senha1234\"}", 200);
            assertTrue(adminLogin.body().path("authenticated").asBoolean());
            String adminCookie = adminLogin.cookie();

            byte[] image = pngImage();
            String productRequest = JSON.writeValueAsString(JSON.createObjectNode()
                    .put("name", "Produto independente do codigo")
                    .put("description", "Criado pelo painel administrativo")
                    .put("price", 39.90)
                    .put("stock", 12)
                    .put("imageBase64", Base64.getEncoder().encodeToString(image))
                    .put("imageType", "image/png"));
            JsonNode created = postJson(client, baseUrl + "/api/admin/products",
                    productRequest, adminCookie, 201);
            long productId = created.path("id").asLong();
            assertTrue(productId > 0);
            assertEquals("/api/products/" + productId + "/image", created.path("imageUrl").asText());

            HttpResponse<byte[]> fetchedImage = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + created.path("imageUrl").asText()))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, fetchedImage.statusCode());
            assertEquals("image/png", fetchedImage.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(java.util.Arrays.equals(image, fetchedImage.body()));

            JsonNode edited = requestJson(client, baseUrl + "/api/admin/products/" + productId,
                    "{\"name\":\"Produto editado\",\"description\":\"Foto preservada\","
                            + "\"price\":44.90,\"stock\":8}", adminCookie, "PUT", 200);
            assertEquals("/api/products/" + productId + "/image", edited.path("imageUrl").asText());

            HttpResponse<String> productList = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/products"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertTrue(productList.body().contains("Produto editado"));
            assertFalse(productList.body().contains("imageBase64"));

            AuthenticatedResponse registration = postAuthenticatedJson(client, baseUrl + "/api/auth/register",
                    "{\"name\":\"Cliente\",\"email\":\"cliente@example.com\","
                            + "\"cpf\":\"111.444.777-35\",\"password\":\"senha1234\"}", 201);
            assertTrue(registration.body().path("authenticated").asBoolean());
            String customerCookie = registration.cookie();
            HttpResponse<String> adminProducts = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/admin/products"))
                            .header("Cookie", customerCookie)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(403, adminProducts.statusCode());

            HttpResponse<String> adminUsers = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/admin/users"))
                            .header("Cookie", adminCookie)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, adminUsers.statusCode());
            assertTrue(adminUsers.body().contains("cliente@example.com"));
            assertFalse(adminUsers.body().contains("password"));

            JsonNode customerAccount = getJson(client, baseUrl + "/api/me", customerCookie, 200);
            long customerId = customerAccount.path("id").asLong();
            orderRepository.create(customerId,
                    Map.of(productService.getAllProducts().get(0).getId(), 1),
                    "PIX", "Rua particular, 123");

            User adminDeletedCustomer = userService.registerUser(
                    user("Cliente removido pelo admin", "removido@example.com", "935.411.347-80"));
            HttpResponse<String> adminDelete = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/admin/users/" + adminDeletedCustomer.getId()))
                            .header("Cookie", adminCookie)
                            .DELETE().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, adminDelete.statusCode(), adminDelete.body());
            assertEquals("Conta excluida #" + adminDeletedCustomer.getId(),
                    userService.getUserById(adminDeletedCustomer.getId()).orElseThrow().getUsername());

            JsonNode wrongPassword = requestJson(client, baseUrl + "/api/me",
                    "{\"password\":\"senha-errada\"}", customerCookie, "DELETE", 400);
            assertTrue(wrongPassword.path("error").asText().contains("senha"));

            HttpResponse<String> selfDelete = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/me"))
                            .header("Cookie", customerCookie)
                            .header("Content-Type", "application/json")
                            .method("DELETE", HttpRequest.BodyPublishers.ofString(
                                    "{\"password\":\"senha1234\"}", StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, selfDelete.statusCode(), selfDelete.body());
            User anonymized = userService.getUserById(customerId).orElseThrow();
            assertEquals("Conta excluida #" + customerId, anonymized.getUsername());
            assertEquals("excluida-" + customerId + "@deleted.invalid", anonymized.getEmail());
            assertEquals("CUSTOMER", anonymized.getRole());
            assertTrue(anonymized.getCpf() == null);
            JdbcOrderRepository.OrderSummary retainedOrder =
                    orderRepository.findByUserId(customerId).get(0);
            assertEquals("Endereco removido pelo titular", retainedOrder.deliveryAddress());

            HttpResponse<String> deletedSession = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/me"))
                            .header("Cookie", customerCookie)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, deletedSession.statusCode());
            assertFalse(JSON.readTree(deletedSession.body()).path("authenticated").asBoolean());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void registrationCreatesSessionAndAllowsLoginWithoutEmailConfirmation() throws Exception {
        Database.initialize("jdbc:h2:mem:register-login;DB_CLOSE_DELAY=-1");
        JdbcUserRepository userRepository = new JdbcUserRepository();
        UserService userService = new UserService(userRepository);
        HttpServer server = WebServer.start(new ProductService(new JdbcProductRepository()),
                userService, new JdbcOrderRepository(), 0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            AuthenticatedResponse response = postAuthenticatedJson(client, baseUrl + "/api/auth/register",
                    "{\"name\":\"Cliente\",\"email\":\"cliente@example.com\","
                            + "\"cpf\":\"111.444.777-35\",\"password\":\"senha1234\"}", 201);
            assertEquals(1, userService.getAllUsers().size());
            assertTrue(response.body().path("authenticated").asBoolean());

            AuthenticatedResponse login = postAuthenticatedJson(client, baseUrl + "/api/auth/login",
                    "{\"email\":\"cliente@example.com\",\"password\":\"senha1234\"}", 200);
            assertTrue(login.body().path("authenticated").asBoolean());
            assertFalse(login.body().has("verificationRequired"));
            assertEquals(404, client.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/auth/verify-email"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).statusCode());
        } finally {
            server.stop(0);
        }
    }

    private static void assertOriginPolicy(HttpClient client, String baseUrl) throws Exception {
        HttpResponse<String> sameOrigin = client.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/origin-policy-test"))
                        .header("Origin", baseUrl)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(404, sameOrigin.statusCode());

        HttpResponse<String> differentOrigin = client.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/origin-policy-test"))
                        .header("Origin", "http://example.com")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(403, differentOrigin.statusCode());
    }

    private static AuthenticatedResponse postAuthenticatedJson(HttpClient client, String url, String body,
                                                               int expectedStatus) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(expectedStatus, response.statusCode(), response.body());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        return new AuthenticatedResponse(JSON.readTree(response.body()), cookie);
    }

    private static JsonNode postJson(HttpClient client, String url, String body,
                                     String cookie, int expectedStatus) throws Exception {
        return requestJson(client, url, body, cookie, "POST", expectedStatus);
    }

    private static JsonNode getJson(HttpClient client, String url, String cookie,
                                    int expectedStatus) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        HttpResponse<String> response = client.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static JsonNode requestJson(HttpClient client, String url, String body,
                                        String cookie, String method, int expectedStatus) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        HttpResponse<String> response = client.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private record AuthenticatedResponse(JsonNode body, String cookie) {
    }

    private static User user(String name, String email, String cpf) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(email);
        user.setCpf(cpf);
        user.setPassword("senha1234");
        return user;
    }

    private static byte[] pngImage() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x3366cc);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
