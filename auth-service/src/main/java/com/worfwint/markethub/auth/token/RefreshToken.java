package com.worfwint.markethub.auth.token;

import java.time.Instant;
import java.util.UUID;

/** A stored refresh token; the token value itself is never stored, only its hash. */
public record RefreshToken(UUID id, UUID accountId, Instant expiresAt, Instant revokedAt, UUID replacedBy) {

	public boolean isRevoked() {
		return revokedAt != null;
	}

	/** Already exchanged for a newer token. Presenting it again suggests it was stolen. */
	public boolean isRotated() {
		return replacedBy != null;
	}

	public boolean isExpired(Instant now) {
		return !expiresAt.isAfter(now);
	}

}
