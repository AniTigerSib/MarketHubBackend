package com.worfwint.markethub.auth.jwt;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SigningKeyRepository {

	/** Arbitrary constant identifying the advisory lock used while creating a key. */
	private static final long KEY_CREATION_LOCK = 0x4d48_4b45_5953L;

	private final JdbcClient jdbc;

	public SigningKeyRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public record StoredKey(String kid, byte[] publicKey, byte[] privateKey) {
	}

	/**
	 * Blocks other auth-service instances from creating a key until the current transaction
	 * ends, so instances starting at the same time all end up with the same key.
	 */
	public void lockKeyCreation() {
		jdbc.sql("SELECT pg_advisory_xact_lock(:lock)").param("lock", KEY_CREATION_LOCK).query((rs, rowNum) -> 1).single();
	}

	public Optional<StoredKey> findActive() {
		return jdbc.sql("""
				SELECT kid, public_key, private_key
				FROM signing_keys
				WHERE retired_at IS NULL
				ORDER BY created_at DESC
				LIMIT 1
				""")
			.query((rs, rowNum) -> new StoredKey(rs.getString("kid"), rs.getBytes("public_key"), rs.getBytes("private_key")))
			.optional();
	}

	/** Public keys tokens may still be signed with: active ones, plus ones retired after {@code retiredAfter}. */
	public List<StoredKey> findPublished(Instant retiredAfter) {
		return jdbc.sql("""
				SELECT kid, public_key
				FROM signing_keys
				WHERE retired_at IS NULL OR retired_at > :retiredAfter
				ORDER BY created_at DESC
				""")
			.param("retiredAfter", OffsetDateTime.ofInstant(retiredAfter, ZoneOffset.UTC))
			.query((rs, rowNum) -> new StoredKey(rs.getString("kid"), rs.getBytes("public_key"), null))
			.list();
	}

	public void insert(String kid, String algorithm, byte[] publicKey, byte[] privateKey) {
		jdbc.sql("""
				INSERT INTO signing_keys (kid, algorithm, public_key, private_key)
				VALUES (:kid, :algorithm, :publicKey, :privateKey)
				""")
			.param("kid", kid)
			.param("algorithm", algorithm)
			.param("publicKey", publicKey)
			.param("privateKey", privateKey)
			.update();
	}

}
