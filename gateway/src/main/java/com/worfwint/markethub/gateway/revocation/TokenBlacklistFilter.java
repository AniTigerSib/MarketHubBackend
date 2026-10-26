package com.worfwint.markethub.gateway.revocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.worfwint.markethub.gateway.redis.RedisAvailability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejects requests whose access token auth-service has revoked: logged out
 * ({@code revoked:jti:<jti>}) or issued before "log out everywhere" ({@code tv:<userId>}
 * higher than the token's "tv" claim). One Redis round trip per request with a Bearer token.
 *
 * <p>This only adds a check; it never lets anything through that the services would reject.
 * Requests without a readable token pass unchanged, and so does everything while Redis is
 * down (revoked tokens then work until they expire, at most one access-token lifetime).
 */
public class TokenBlacklistFilter extends OncePerRequestFilter {

	static final String REVOKED_JTI_PREFIX = "revoked:jti:";

	static final String TOKEN_VERSION_PREFIX = "tv:";

	private final StringRedisTemplate redis;

	private final RedisAvailability availability;

	public TokenBlacklistFilter(StringRedisTemplate redis, RedisAvailability availability) {
		this.redis = redis;
		this.availability = availability;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Optional<AccessTokenClaims> claims =
				AccessTokenClaims.fromAuthorizationHeader(request.getHeader(HttpHeaders.AUTHORIZATION));
		if (claims.isPresent() && availability.shouldTry() && isRevoked(claims.get())) {
			reject(response);
			return;
		}
		chain.doFilter(request, response);
	}

	private boolean isRevoked(AccessTokenClaims claims) {
		List<String> keys = new ArrayList<>(2);
		if (claims.jti() != null) {
			keys.add(REVOKED_JTI_PREFIX + claims.jti());
		}
		if (claims.subject() != null) {
			keys.add(TOKEN_VERSION_PREFIX + claims.subject());
		}
		List<String> values;
		try {
			values = redis.opsForValue().multiGet(keys);
			availability.succeeded();
		}
		catch (DataAccessException ex) {
			availability.failed(ex);
			return false;
		}
		if (values == null) {
			return false;
		}
		int i = 0;
		if (claims.jti() != null && values.get(i++) != null) {
			return true;
		}
		return claims.subject() != null && claims.tokenVersion() < parseVersion(values.get(i));
	}

	/** No entry: nothing revoked recently. */
	private static long parseVersion(String stored) {
		if (stored == null) {
			return Long.MIN_VALUE;
		}
		try {
			return Long.parseLong(stored);
		}
		catch (NumberFormatException ex) {
			return Long.MIN_VALUE;
		}
	}

	private static void reject(HttpServletResponse response) throws IOException {
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
				"Bearer error=\"invalid_token\", error_description=\"The access token has been revoked\"");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("""
				{"status":401,"error":"Unauthorized","message":"The access token has been revoked."}""");
	}

}
