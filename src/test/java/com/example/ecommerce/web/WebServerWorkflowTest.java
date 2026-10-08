package com.example.ecommerce.web;

import com.example.ecommerce.model.User;
import com.example.ecommerce.persistence.Database;
import com.example.ecommerce.repository.JdbcOrderRepository;
import com.example.ecommerce.repository.JdbcProductRepository;
import com.example.ecommerce.repository.JdbcUserRepository;
import com.example.ecommerce.security.EmailCodeService;
import com.example.ecommerce.service.ProductService;
import com.example.ecommerce.service.UserService;
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
import java.util.concurrent.atomic.AtomicReference;

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
        User admin = userService.registerUser(user("Admin", "admin@example.com", "529.982.247-25"));
        admin.setRole("ADMIN");
        userService.updateUser(admin);

        AtomicReference<String> sentCode = new AtomicReference<>();
        EmailCodeService emailCodeService = new EmailCodeService(
                (email, name, code) -> sentCode.set(code));
        HttpServer server = WebServer.start(
                productService, userService, new JdbcOrderRepository(), emailCodeService, 0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

            JsonNode adminLogin = postJson(client, baseUrl + "/api/auth/login",
                    "{\"email\":\"admin@example.com\",\"password\":\"senha1234\"}", null, 202);
            assertTrue(adminLogin.path("verificationRequired").asBoolean());
            String adminCookie = verifyLogin(client, baseUrl, adminLogin, sentCode);

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

            JsonNode registration = postJson(client, baseUrl + "/api/auth/register",
                    "{\"name\":\"Cliente\",\"email\":\"cliente@example.com\","
                            + "\"cpf\":\"111.444.777-35\",\"password\":\"senha1234\"}", null, 202);
            String customerCookie = verifyLogin(client, baseUrl, registration, sentCode);
            HttpResponse<String> adminProducts = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/admin/products"))
                            .header("Cookie", customerCookie)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(403, adminProducts.statusCode());
        } finally {
            server.stop(0);
        }
    }

    private static String verifyLogin(HttpClient client, String baseUrl, JsonNode challenge,
                                      AtomicReference<String> sentCode) throws Exception {
        String body = JSON.writeValueAsString(JSON.createObjectNode()
                .put("challengeId", challenge.path("challengeId").asText())
                .put("code", sentCode.get()));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/auth/verify-email"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode verified = JSON.readTree(response.body());
        assertTrue(verified.path("authenticated").asBoolean());
        return response.headers().firstValue("Set-Cookie").orElseThrow()
                .split(";", 2)[0];
    }

    private static JsonNode postJson(HttpClient client, String url, String body,
                                     String cookie, int expectedStatus) throws Exception {
        return requestJson(client, url, body, cookie, "POST", expectedStatus);
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
