package com.example.ecommerce;

import com.example.ecommerce.persistence.Database;
import com.example.ecommerce.repository.JdbcProductRepository;
import com.example.ecommerce.repository.JdbcOrderRepository;
import com.example.ecommerce.repository.JdbcUserRepository;
import com.example.ecommerce.service.ProductService;
import com.example.ecommerce.service.UserService;
import com.example.ecommerce.util.DataSeeder;
import com.example.ecommerce.web.WebServer;

import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        Database.initialize();
        JdbcProductRepository productRepository = new JdbcProductRepository();
        JdbcUserRepository userRepository = new JdbcUserRepository();

        ProductService productService = new ProductService(productRepository);
        UserService userService = new UserService(userRepository);

        if (productService.getAllProducts().isEmpty()) {
            DataSeeder.seedProducts(productService);
        }
        WebServer.start(productService, userService, new JdbcOrderRepository());
        String port = System.getenv().getOrDefault("ECOMMERCE_PORT", "8080");
        System.out.println("Loja disponivel em http://localhost:" + port);
        System.out.println("Na rede local, acesse http://<IP-LOCAL-DESTE-COMPUTADOR>:" + port);
    }
}