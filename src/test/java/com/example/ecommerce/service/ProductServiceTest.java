package com.example.ecommerce.service;

import com.example.ecommerce.model.Product;
import com.example.ecommerce.repository.ProductRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductServiceTest {
    private final ProductService service = new ProductService(new InMemoryProductRepository());

    @Test
    void addProductRejectsInvalidName() {
        Product product = new Product(null, " ", "Desc", 10.0, 2);
        assertThrows(IllegalArgumentException.class, () -> service.addProduct(product));
    }

    @Test
    void addProductRejectsNegativePrice() {
        Product product = new Product(null, "Notebook", "Desc", -1.0, 2);
        assertThrows(IllegalArgumentException.class, () -> service.addProduct(product));
    }

    @Test
    void deleteProductRejectsInvalidIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> service.deleteProduct(0L));
    }

    private static final class InMemoryProductRepository implements ProductRepository {
        @Override
        public List<Product> findAll() {
            return List.of();
        }

        @Override
        public Optional<Product> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public Product save(Product product) {
            return product;
        }

        @Override
        public void deleteById(Long id) {
        }
    }
}
