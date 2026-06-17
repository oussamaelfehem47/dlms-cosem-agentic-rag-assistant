package com.company.dlms.api;

import java.time.Instant;
import java.util.UUID;

public record CurrentUserProfileResponse(
        UUID user_id,
        String username,
        String email,
        String role,
        Instant created_at,
        boolean active
) {}
