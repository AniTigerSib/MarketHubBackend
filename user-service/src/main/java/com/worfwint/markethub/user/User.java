package com.worfwint.markethub.user;

import java.time.Instant;

public record User(String username, String fullName, String email, Instant lastLoginAt) {

	public User withLastLoginAt(Instant lastLoginAt) {
		return new User(username, fullName, email, lastLoginAt);
	}

}
