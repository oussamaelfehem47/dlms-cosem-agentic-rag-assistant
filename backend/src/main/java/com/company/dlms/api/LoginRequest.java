package com.company.dlms.api;

public record LoginRequest(String email, String username, String password) {
    public String resolvedIdentifier() {
        return (email != null && !email.isBlank()) ? email : username;
    }
}
