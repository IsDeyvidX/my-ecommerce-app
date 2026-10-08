package com.example.ecommerce.repository;

import com.example.ecommerce.model.Product;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryProductRepository implements ProductRepository {
    private final Map<Long, Product> products = new HashMap<>();

    @Override
    public List<Product> findAll() {
        return new ArrayList<>(products.values());
    }

    @Override
    public Optional<Product> findById(Long id) {
        return Optional.ofNullable(products.get(id));
    }

    @Override
    public Product save(Product product) {
        if (product.getId() == null) {
            long nextId = products.keySet().stream().mapToLong(Long::longValue).max().orElse(0L) + 1L;
            product.setId(nextId);
        }
        products.put(product.getId(), product);
        return product;
    }

    @Override
    public void deleteById(Long id) {
        products.remove(id);
    }
}
