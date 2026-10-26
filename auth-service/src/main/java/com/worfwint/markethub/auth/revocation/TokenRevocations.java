package com.worfwint.markethub.auth.revocation;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.worfwint.markethub.auth.jwt.JwtProperties;

/**
 * Writes the access-token blacklist to Redis. The gateway reads it and rejects listed tokens.
 * Entries expire once the tokens they block would have expired anyway.
 */
@Component
public class TokenRevocations {

	/** {@code revoked:jti:<jti>}: one revoked access token. */
	public static final String REVOKED_JTI_PREFIX = "revoked:jti:";

	/** {@code tv:<userId>}: tokens with a lower "tv" claim are revoked. */
	public static final String TOKEN_VERSION_PREFIX = "tv:";

	/**
	 * Only ever raises the stored version: two revocations for the same user can reach Redis in
	 * either order, and the lower one must not overwrite the higher one.
	 */
	private static final RedisScript<Long> RAISE_VERSION = RedisScript.of("""
			local current = redis.call('GET', KEYS[1])
			if current == false or tonumber(current) < tonumber(ARGV[1]) then
				redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
				return 1
			end
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;

	private final Duration accessTokenTtl;

	public TokenRevocations(StringRedisTemplate redis, JwtProperties jwtProperties) {
		this.redis = redis;
		this.accessTokenTtl = jwtProperties.accessTokenTtl();
	}

	/**
	 * Blacklists one access token.
	 *
	 * @param remainingLifetime time until the token expires; the entry is kept that long
	 * @throws RevocationUnavailableException if Redis can't be reached
	 */
	public void revokeAccessToken(String jti, Duration remainingLifetime) {
		Duration ttl = remainingLifetime.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : remainingLifetime;
		try {
			redis.opsForValue().set(REVOKED_JTI_PREFIX + jti, "1", ttl);
		}
		catch (DataAccessException ex) {
			throw new RevocationUnavailableException(ex);
		}
	}

	/**
	 * Publishes a user's new token version, which revokes every access token with a lower one.
	 * Kept for one access-token lifetime: after that, all of those tokens have expired.
	 *
	 * @throws RevocationUnavailableException if Redis can't be reached
	 */
	public void publishTokenVersion(UUID userId, int version) {
		try {
			redis.execute(RAISE_VERSION, List.of(TOKEN_VERSION_PREFIX + userId), Integer.toString(version),
					Long.toString(accessTokenTtl.toSeconds()));
		}
		catch (DataAccessException ex) {
			throw new RevocationUnavailableException(ex);
		}
	}

	/** Redis couldn't be reached, so the blacklist wasn't updated. */
	public static class RevocationUnavailableException extends RuntimeException {

		public RevocationUnavailableException(Throwable cause) {
			super("Token blacklist is unavailable", cause);
		}

	}

}
