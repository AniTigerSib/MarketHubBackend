package com.worfwint.markethub.auth.account;

import java.util.List;
import java.util.UUID;

/**
 * @param tokenVersion copied into every access token as the "tv" claim; increased to revoke
 *        all of the account's access tokens at once
 */
public record Account(UUID id, String username, String email, String passwordHash, boolean enabled,
		List<String> roles, int tokenVersion) {
}
