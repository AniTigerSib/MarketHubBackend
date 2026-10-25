package com.worfwint.markethub.auth.account;

import java.util.List;
import java.util.UUID;

public record Account(UUID id, String username, String email, String passwordHash, boolean enabled,
		List<String> roles) {
}
