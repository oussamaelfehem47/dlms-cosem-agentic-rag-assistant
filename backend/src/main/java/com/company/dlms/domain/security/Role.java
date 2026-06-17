package com.company.dlms.domain.security;
 
public enum Role {
    VIEWER,
    ENGINEER,
    ADMIN;
 
    public String toAuthority() {
        return "ROLE_" + this.name();
    }
 
    public static Role fromString(String role) {
        if (role == null) return VIEWER;
        try {
            return Role.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            return VIEWER;
        }
    }
}
