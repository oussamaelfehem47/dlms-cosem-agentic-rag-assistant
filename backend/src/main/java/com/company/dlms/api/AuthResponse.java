package com.company.dlms.api;
 
import java.util.UUID;
 
public record AuthResponse(
    String access_token,
    String refresh_token,
    UUID user_id,
    String username,
    String role
) {}
