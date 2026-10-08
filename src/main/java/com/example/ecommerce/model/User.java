package com.example.ecommerce.model;

public class User {
    private Long id;
    private String username;
    private String email;
    private String password;
    private String cpf;
    private String role;

    public User() {
    }

    public User(Long id, String username, String email, String password) {
        this(id, username, email, password, null, "CUSTOMER");
    }

    public User(Long id, String username, String email, String password, String cpf, String role) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.password = password;
        this.cpf = cpf;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getCpf() {
        return cpf;
    }

    public void setCpf(String cpf) {
        this.cpf = cpf;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}