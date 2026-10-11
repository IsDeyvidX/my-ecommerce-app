package com.example.ecommerce.web;

import com.example.ecommerce.model.Product;
import com.example.ecommerce.model.User;
import com.example.ecommerce.repository.JdbcOrderRepository;
import com.example.ecommerce.service.ProductService;
import com.example.ecommerce.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WebServer {
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("ECOMMERCE_PORT", "8080"));
    private static final String BIND_ADDRESS = System.getenv()
            .getOrDefault("ECOMMERCE_BIND_ADDRESS", "0.0.0.0");
    private static final int MAX_REQUEST_BYTES = 65_536;
    private static final int MAX_PRODUCT_REQUEST_BYTES = 3_000_000;
    private static final int MAX_PRODUCT_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final long SESSION_SECONDS = 43_200;
    private static final String SESSION_COOKIE = "CASANOVA_SESSION";
    private static final String HTML_CONTENT_TYPE = "text/html; charset=utf-8";
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private record Session(long userId, Instant expiresAt) {
    }

    private WebServer() {
    }

    public static void start(ProductService productService, UserService userService,
                             JdbcOrderRepository orderRepository) throws IOException {
        start(productService, userService, orderRepository, BIND_ADDRESS, PORT);
    }

    public static HttpServer start(ProductService productService, UserService userService,
                                   JdbcOrderRepository orderRepository,
                                   int port) throws IOException {
        return start(productService, userService, orderRepository, "127.0.0.1", port);
    }

    static HttpServer start(ProductService productService, UserService userService,
                            JdbcOrderRepository orderRepository,
                            String bindAddress, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        server.createContext("/", exchange -> handleRequest(
                exchange, productService, userService, orderRepository));
        server.start();
        return server;
    }

    private static void handleRequest(HttpExchange exchange, ProductService productService,
                                      UserService userService,
                                      JdbcOrderRepository orderRepository) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                sendIndex(exchange);
                return;
            }
            if (path.startsWith("/api/") && !isReadMethod(method) && !isAllowedOrigin(exchange)) {
                sendError(exchange, 403, "Origem da requisicao nao permitida.");
                return;
            }
            if ("/api/products".equals(path) && "GET".equals(method)) {
                sendJson(exchange, 200, productService.getAllProducts());
                return;
            }
            if (path.matches("^/api/products/\\d+/image$")
                    && "GET".equals(method)) {
                sendProductImage(exchange, path, productService);
                return;
            }
            if ("/api/auth/register".equals(path) && "POST".equals(method)) {
                register(exchange, userService);
                return;
            }
            if ("/api/auth/login".equals(path) && "POST".equals(method)) {
                login(exchange, userService);
                return;
            }
            if ("/api/auth/logout".equals(path) && "POST".equals(method)) {
                logout(exchange);
                return;
            }
            if ("/api/me".equals(path) && "GET".equals(method)) {
                currentAccount(exchange, userService);
                return;
            }
            if ("/api/me".equals(path) && "DELETE".equals(method)) {
                deleteCurrentAccount(exchange, userService);
                return;
            }
            if ("/api/orders".equals(path)) {
                orders(exchange, method, userService, orderRepository);
                return;
            }
            if ("/api/admin/users".equals(path) && "GET".equals(method)) {
                listAdminUsers(exchange, userService);
                return;
            }
            if (path.matches("^/api/admin/users/\\d+$") && "DELETE".equals(method)) {
                deleteAdminUser(exchange, path, userService);
                return;
            }
            if ("/api/admin/products".equals(path)
                    || path.matches("^/api/admin/products/\\d+$")) {
                manageProducts(exchange, method, path, productService, userService);
                return;
            }
            sendError(exchange, 404, "Pagina ou recurso nao encontrado.");
        } catch (NotAuthorizedException exception) {
            sendError(exchange, 401, exception.getMessage());
        } catch (ForbiddenException exception) {
            sendError(exchange, 403, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, exception.getMessage());
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            System.err.println("Falha ao processar requisicao web: " + exception.getMessage());
            sendError(exchange, 500, "Nao foi possivel concluir a requisicao.");
        } finally {
            exchange.close();
        }
    }

    private static boolean isReadMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    private static boolean isAllowedOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String requestHost = exchange.getRequestHeaders().getFirst("Host");
        return origin == null || origin.equals("http://localhost:" + PORT)
                || origin.equals("http://127.0.0.1:" + PORT)
                || requestHost != null && origin.equalsIgnoreCase("http://" + requestHost);
    }

    private static void register(HttpExchange exchange, UserService userService) throws IOException {
        JsonNode body = readJson(exchange);
        User user = new User();
        user.setUsername(requiredText(body, "name", 100));
        user.setEmail(requiredText(body, "email", 255));
        user.setCpf(requiredText(body, "cpf", 30));
        user.setPassword(requiredPassword(body));
        User registered = userService.registerUser(user);
        createSession(exchange, registered);
        sendJson(exchange, 201, accountJson(registered));
    }

    private static void login(HttpExchange exchange, UserService userService) throws IOException {
        JsonNode body = readJson(exchange);
        String email = requiredText(body, "email", 255);
        String password = requiredPassword(body);
        User user = userService.authenticate(email, password)
                .orElseThrow(() -> new NotAuthorizedException("Email ou senha incorretos."));
        createSession(exchange, user);
        sendJson(exchange, 200, accountJson(user));
    }

    private static void logout(HttpExchange exchange) throws IOException {
        String sessionId = sessionId(exchange);
        if (sessionId != null) {
            SESSIONS.remove(sessionId);
        }
        exchange.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        sendJson(exchange, 200, Map.of("authenticated", false));
    }

    private static void currentAccount(HttpExchange exchange, UserService userService) throws IOException {
        User user = currentUser(exchange, userService);
        if (user == null) {
            sendJson(exchange, 200, Map.of("authenticated", false));
            return;
        }
        sendJson(exchange, 200, accountJson(user));
    }

    private static void deleteCurrentAccount(HttpExchange exchange, UserService userService) throws IOException {
        User user = requireUser(exchange, userService);
        JsonNode body = readJson(exchange);
        userService.deleteAccount(user.getId(), requiredPassword(body));
        invalidateUserSessions(user.getId());
        exchange.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        sendJson(exchange, 200, Map.of("deleted", true));
    }

    private static void listAdminUsers(HttpExchange exchange, UserService userService) throws IOException {
        requireAdmin(exchange, userService);
        List<Map<String, Object>> users = userService.getAllUsers().stream()
                .map(user -> Map.<String, Object>of(
                        "id", user.getId(),
                        "name", user.getUsername(),
                        "email", user.getEmail(),
                        "role", user.getRole()))
                .toList();
        sendJson(exchange, 200, users);
    }

    private static void deleteAdminUser(HttpExchange exchange, String path, UserService userService)
            throws IOException {
        User admin = requireAdmin(exchange, userService);
        long userId = parseUserId(path);
        if (admin.getId().equals(userId)) {
            throw new IllegalArgumentException("Nao e possivel excluir a propria conta por este painel.");
        }
        User target = userService.getUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Conta nao encontrada."));
        if (!"CUSTOMER".equals(target.getRole())) {
            throw new IllegalArgumentException("O painel permite excluir somente contas de clientes.");
        }
        userService.anonymizeAccount(userId);
        invalidateUserSessions(userId);
        sendJson(exchange, 200, Map.of("deleted", true));
    }

    private static long parseUserId(String path) {
        try {
            long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("O identificador da conta e invalido.");
        }
    }

    private static void invalidateUserSessions(long userId) {
        SESSIONS.entrySet().removeIf(entry -> entry.getValue().userId() == userId);
    }

    private static ObjectNode accountJson(User user) {
        ObjectNode account = JSON.createObjectNode();
        account.put("authenticated", true);
        account.put("id", user.getId());
        account.put("name", user.getUsername());
        account.put("email", user.getEmail());
        account.put("role", user.getRole());
        return account;
    }

    private static void orders(HttpExchange exchange, String method, UserService userService,
                               JdbcOrderRepository orderRepository) throws IOException {
        User user = requireUser(exchange, userService);
        if ("GET".equals(method)) {
            sendJson(exchange, 200, orderRepository.findByUserId(user.getId()));
            return;
        }
        if (!"POST".equals(method)) {
            methodNotAllowed(exchange, "GET, POST");
            return;
        }

        JsonNode body = readJson(exchange);
        JsonNode itemNodes = body.get("items");
        if (itemNodes == null || !itemNodes.isArray()) {
            throw new IllegalArgumentException("A sacola esta vazia ou invalida.");
        }
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (JsonNode item : itemNodes) {
            JsonNode productId = item.get("productId");
            JsonNode quantity = item.get("quantity");
            if (productId == null || !productId.canConvertToLong()
                    || quantity == null || !quantity.canConvertToInt()) {
                throw new IllegalArgumentException("A sacola contem um produto ou quantidade invalida.");
            }
            if (quantities.putIfAbsent(productId.longValue(), quantity.intValue()) != null) {
                throw new IllegalArgumentException("A sacola contem produtos duplicados.");
            }
        }

        String paymentMethod = requiredText(body, "paymentMethod", 20);
        String deliveryAddress = requiredText(body, "deliveryAddress", 500);
        JdbcOrderRepository.CreatedOrder order = orderRepository.create(
                user.getId(), quantities, paymentMethod, deliveryAddress);
        sendJson(exchange, 201, Map.of(
                "id", order.id(),
                "total", order.total(),
                "paymentMethod", paymentMethod,
                "deliveryAddress", deliveryAddress.trim()));
    }

    private static void manageProducts(HttpExchange exchange, String method, String path,
                                       ProductService productService, UserService userService)
            throws IOException {
        requireAdmin(exchange, userService);
        if ("GET".equals(method) && "/api/admin/products".equals(path)) {
            sendJson(exchange, 200, productService.getAllProducts());
            return;
        }
        if ("POST".equals(method) && "/api/admin/products".equals(path)) {
            Product product = readProduct(readJson(exchange, MAX_PRODUCT_REQUEST_BYTES), true);
            sendJson(exchange, 201, productService.addProduct(product));
            return;
        }

        if (!path.matches("^/api/admin/products/\\d+$")) {
            methodNotAllowed(exchange, "GET, POST");
            return;
        }
        long productId = parseProductId(path);
        if ("PUT".equals(method)) {
            if (productService.getProductById(productId).isEmpty()) {
                throw new IllegalArgumentException("O produto que voce tentou editar nao existe.");
            }
            Product product = readProduct(readJson(exchange, MAX_PRODUCT_REQUEST_BYTES), false);
            product.setId(productId);
            sendJson(exchange, 200, productService.updateProduct(product));
        } else if ("DELETE".equals(method)) {
            productService.deleteProduct(productId);
            sendJson(exchange, 200, Map.of("deleted", true));
        } else {
            methodNotAllowed(exchange, "PUT, DELETE");
        }
    }

    private static Product readProduct(JsonNode body, boolean imageRequired) {
        Product product = new Product();
        product.setName(requiredText(body, "name", 150));
        String description = requiredText(body, "description", 1000);
        product.setDescription(description);
        JsonNode price = body.get("price");
        JsonNode stock = body.get("stock");
        if (price == null || !price.isNumber() || !Double.isFinite(price.doubleValue())
                || price.doubleValue() < 0 || price.doubleValue() > 99_999_999.99) {
            throw new IllegalArgumentException("Informe um preco valido e nao negativo.");
        }
        if (stock == null || !stock.canConvertToInt()
                || stock.intValue() < 0 || stock.intValue() > 2_147_483_647) {
            throw new IllegalArgumentException("Informe uma quantidade valida e nao negativa.");
        }
        product.setPrice(BigDecimal.valueOf(price.doubleValue()).setScale(2, java.math.RoundingMode.HALF_UP)
                .doubleValue());
        product.setStock(stock.intValue());
        applyProductImage(body, product, imageRequired);
        return product;
    }

    private static void applyProductImage(JsonNode body, Product product, boolean required) {
        JsonNode imageValue = body.get("imageBase64");
        if (imageValue == null || imageValue.isNull() || !imageValue.isTextual()
                || imageValue.textValue().isBlank()) {
            if (required) {
                throw new IllegalArgumentException("Anexe uma foto PNG ou JPEG para o produto.");
            }
            return;
        }
        String encoded = imageValue.textValue();
        if (encoded.length() > ((MAX_PRODUCT_IMAGE_BYTES + 2L) / 3L) * 4L) {
            throw new IllegalArgumentException("A foto deve ter no maximo 2 MB.");
        }
        byte[] image;
        try {
            image = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("O arquivo de imagem e invalido.");
        }
        if (image.length == 0 || image.length > MAX_PRODUCT_IMAGE_BYTES) {
            throw new IllegalArgumentException("A foto deve ter no maximo 2 MB.");
        }
        String declaredType = requiredText(body, "imageType", 20);
        String detectedType = detectImageType(image);
        if (!declaredType.equals(detectedType)) {
            throw new IllegalArgumentException("O tipo do arquivo nao corresponde a imagem enviada.");
        }
        product.setImageData(image);
        product.setImageType(detectedType);
    }

    private static String detectImageType(byte[] image) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
            if (input == null) {
                throw new IllegalArgumentException("O arquivo enviado nao e uma imagem valida.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Use uma imagem PNG ou JPEG valida.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                String contentType = switch (format.toLowerCase(java.util.Locale.ROOT)) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    default -> throw new IllegalArgumentException("Use uma imagem PNG ou JPEG.");
                };
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > 12_000_000) {
                    throw new IllegalArgumentException("A resolucao da imagem excede o limite permitido.");
                }
                if (reader.read(0) == null) {
                    throw new IllegalArgumentException("O arquivo enviado nao e uma imagem valida.");
                }
                return contentType;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("O arquivo enviado nao e uma imagem valida.");
        }
    }

    private static void sendProductImage(HttpExchange exchange, String path,
                                         ProductService productService) throws IOException {
        long productId;
        try {
            productId = Long.parseLong(path.substring("/api/products/".length(),
                    path.length() - "/image".length()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("O identificador do produto e invalido.");
        }
        Product product = productService.getProductById(productId).orElse(null);
        if (product == null || product.getImageData() == null) {
            sendError(exchange, 404, "A foto do produto nao foi encontrada.");
            return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        sendResponse(exchange, 200, product.getImageType(), product.getImageData());
    }

    private static long parseProductId(String path) {
        try {
            long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("O identificador do produto e invalido.");
        }
    }

    private static User requireAdmin(HttpExchange exchange, UserService userService) {
        User user = requireUser(exchange, userService);
        if (!"ADMIN".equals(user.getRole())) {
            throw new ForbiddenException("Acesso restrito ao administrador.");
        }
        return user;
    }

    private static User requireUser(HttpExchange exchange, UserService userService) {
        User user = currentUser(exchange, userService);
        if (user == null) {
            throw new NotAuthorizedException("Entre na sua conta para continuar.");
        }
        return user;
    }

    private static User currentUser(HttpExchange exchange, UserService userService) {
        String sessionId = sessionId(exchange);
        if (sessionId == null) {
            return null;
        }
        Session session = SESSIONS.get(sessionId);
        if (session == null) {
            return null;
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            SESSIONS.remove(sessionId, session);
            return null;
        }
        return userService.getUserById(session.userId()).orElse(null);
    }

    private static void createSession(HttpExchange exchange, User user) {
        Instant now = Instant.now();
        SESSIONS.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        String sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        SESSIONS.put(sessionId, new Session(user.getId(), now.plusSeconds(SESSION_SECONDS)));
        exchange.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=" + sessionId + "; Path=/; HttpOnly; SameSite=Lax; Max-Age="
                        + SESSION_SECONDS);
    }

    private static String sessionId(HttpExchange exchange) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return null;
        }
        for (String cookieHeader : cookies) {
            for (String cookie : cookieHeader.split(";")) {
                String[] parts = cookie.trim().split("=", 2);
                if (parts.length == 2 && SESSION_COOKIE.equals(parts[0])) {
                    return parts[1];
                }
            }
        }
        return null;
    }

    private static JsonNode readJson(HttpExchange exchange) throws IOException {
        return readJson(exchange, MAX_REQUEST_BYTES);
    }

    private static JsonNode readJson(HttpExchange exchange, int maximumBytes) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT)
                .startsWith("application/json")) {
            throw new IllegalArgumentException("O formato da requisicao deve ser JSON.");
        }
        byte[] body = exchange.getRequestBody().readNBytes(maximumBytes + 1);
        if (body.length > maximumBytes) {
            throw new IllegalArgumentException("A requisicao excede o tamanho permitido.");
        }
        try {
            JsonNode json = JSON.readTree(body);
            if (json == null || !json.isObject()) {
                throw new IllegalArgumentException("O corpo da requisicao e invalido.");
            }
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("O corpo da requisicao contem JSON invalido.");
        }
    }

    private static String requiredText(JsonNode body, String field, int maxLength) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("O campo " + field + " e obrigatorio.");
        }
        String text = value.textValue().trim();
        if (text.isEmpty() || text.length() > maxLength) {
            throw new IllegalArgumentException("O campo " + field + " esta vazio ou excede o limite permitido.");
        }
        return text;
    }

    private static String requiredPassword(JsonNode body) {
        JsonNode value = body.get("password");
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("O campo password e obrigatorio.");
        }
        String password = value.textValue();
        if (password.isEmpty() || password.length() > 200) {
            throw new IllegalArgumentException("A senha esta vazia ou excede o limite permitido.");
        }
        return password;
    }

    private static void sendIndex(HttpExchange exchange) throws IOException {
        try (InputStream input = WebServer.class.getResourceAsStream("/web/index.html")) {
            if (input == null) {
                sendResponse(exchange, 500, "text/plain; charset=utf-8",
                        "A interface da loja nao foi encontrada.");
                return;
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            sendResponse(exchange, 200, HTML_CONTENT_TYPE, input.readAllBytes());
        }
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        if (exchange.getResponseCode() != -1) {
            return;
        }
        sendJson(exchange, status, Map.of("error", message == null ? "Requisicao invalida." : message));
    }

    private static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        sendError(exchange, 405, "Metodo nao permitido.");
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        sendResponse(exchange, status, JSON_CONTENT_TYPE, JSON.writeValueAsBytes(value));
    }

    private static void sendResponse(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        sendResponse(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendResponse(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; "
                        + "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static final class NotAuthorizedException extends RuntimeException {
        private NotAuthorizedException(String message) {
            super(message);
        }
    }

    private static final class ForbiddenException extends RuntimeException {
        private ForbiddenException(String message) {
            super(message);
        }
    }
}
