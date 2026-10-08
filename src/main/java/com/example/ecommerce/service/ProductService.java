package com.example.ecommerce.service;

import com.example.ecommerce.model.Product;
import com.example.ecommerce.repository.ProductRepository;

import java.util.List;
import java.util.Optional;

public class ProductService {
    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Product addProduct(Product product) {
        validateProduct(product);
        return productRepository.save(product);
    }

    public Optional<Product> getProductById(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("O identificador do produto e invalido.");
        }
        return productRepository.findById(id);
    }

    public Product updateProduct(Product product) {
        if (product == null || product.getId() == null || product.getId() <= 0) {
            throw new IllegalArgumentException("O identificador do produto e invalido.");
        }
        validateProduct(product);
        return productRepository.save(product);
    }

    public void deleteProduct(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("O identificador do produto e invalido.");
        }
        productRepository.deleteById(id);
    }

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    private void validateProduct(Product product) {
        if (product == null) {
            throw new IllegalArgumentException("O produto informado e invalido.");
        }

        String name = product.getName() == null ? "" : product.getName().trim();
        if (name.isEmpty() || name.length() > 150) {
            throw new IllegalArgumentException("Informe um nome para o produto entre 1 e 150 caracteres.");
        }

        String description = product.getDescription() == null ? "" : product.getDescription().trim();
        if (description.length() > 1000) {
            throw new IllegalArgumentException("A descricao do produto excede o limite permitido.");
        }

        double price = product.getPrice();
        if (!Double.isFinite(price) || price < 0 || price > 99_999_999.99) {
            throw new IllegalArgumentException("Informe um preco valido e nao negativo.");
        }

        int stock = product.getStock();
        if (stock < 0) {
            throw new IllegalArgumentException("Informe uma quantidade valida e nao negativa.");
        }

        product.setName(name);
        product.setDescription(description);
    }
}