package com.worfwint.markethub.user;

import java.util.UUID;

public record UserProfile(UUID id, String username, String email, String displayName, String avatarUrl,
		String phone) {
}
