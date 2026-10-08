package com.example.ecommerce.util;

import com.example.ecommerce.model.Product;
import com.example.ecommerce.service.ProductService;

public class DataSeeder {

    public static void seedProducts(ProductService productService) {
        productService.addProduct(new Product(null, "Laptop", "High-performance laptop", 999.99, 10));
        productService.addProduct(new Product(null, "Smartphone", "Latest model smartphone", 699.99, 20));
        productService.addProduct(new Product(null, "Headphones", "Noise-cancelling headphones", 249.50, 15));
    }
}