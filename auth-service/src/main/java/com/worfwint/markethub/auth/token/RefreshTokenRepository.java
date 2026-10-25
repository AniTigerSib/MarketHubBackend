package com.worfwint.markethub.auth.token;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshTokenRepository {

	private final JdbcClient jdbc;

	public RefreshTokenRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(UUID id, UUID accountId, byte[] tokenHash, Instant expiresAt) {
		jdbc.sql("""
				INSERT INTO refresh_tokens (id, account_id, token_hash, expires_at)
				VALUES (:id, :accountId, :tokenHash, :expiresAt)
				""")
			.param("id", id)
			.param("accountId", accountId)
			.param("tokenHash", tokenHash)
			.param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
			.update();
	}

	/** Locks the row until the transaction ends, so a token can't be rotated twice in parallel. */
	public Optional<RefreshToken> findByHashForUpdate(byte[] tokenHash) {
		return jdbc.sql("""
				SELECT id, account_id, expires_at, revoked_at, replaced_by
				FROM refresh_tokens
				WHERE token_hash = :tokenHash
				FOR UPDATE
				""")
			.param("tokenHash", tokenHash)
			.query((rs, rowNum) -> {
				OffsetDateTime revokedAt = rs.getObject("revoked_at", OffsetDateTime.class);
				return new RefreshToken(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
						rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
						revokedAt != null ? revokedAt.toInstant() : null, rs.getObject("replaced_by", UUID.class));
			})
			.optional();
	}

	public void markRotated(UUID id, UUID replacedBy) {
		jdbc.sql("UPDATE refresh_tokens SET revoked_at = now(), replaced_by = :replacedBy WHERE id = :id")
			.param("id", id)
			.param("replacedBy", replacedBy)
			.update();
	}

	public void revokeByHash(byte[] tokenHash) {
		jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = :tokenHash AND revoked_at IS NULL")
			.param("tokenHash", tokenHash)
			.update();
	}

	public int revokeAllForAccount(UUID accountId) {
		return jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE account_id = :accountId AND revoked_at IS NULL")
			.param("accountId", accountId)
			.update();
	}

}
