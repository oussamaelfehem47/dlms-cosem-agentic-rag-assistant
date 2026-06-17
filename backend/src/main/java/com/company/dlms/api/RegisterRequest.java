package com.company.dlms.api;

import com.company.dlms.domain.security.Role;

public record RegisterRequest(
        String username,
        String email,
        String password,
        Role role
) {}
