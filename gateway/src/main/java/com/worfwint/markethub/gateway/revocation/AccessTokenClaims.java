package com.worfwint.markethub.gateway.revocation;

import java.util.Base64;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The claims the blacklist check needs, read from a Bearer token <b>without verifying it</b>.
 * That's safe only because they're used to reject, never to accept: a forged value can at
 * most get the forger's own request rejected, and the services still verify every token.
 *
 * @param jti token id, or null
 * @param subject user id, or null
 * @param tokenVersion the "tv" claim; 0 for tokens issued before versions existed
 */
public record AccessTokenClaims(String jti, String subject, long tokenVersion) {

	private static final String BEARER_PREFIX = "Bearer ";

	/** Longer ids aren't ones auth-service issues; ignoring them keeps Redis keys small. */
	private static final int MAX_ID_LENGTH = 64;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * @param authorizationHeader the {@code Authorization} header value, may be null
	 * @return the claims, or empty if there's no Bearer token or it isn't a readable JWT
	 */
	public static Optional<AccessTokenClaims> fromAuthorizationHeader(String authorizationHeader) {
		if (authorizationHeader == null
				|| !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			return Optional.empty();
		}
		String[] parts = authorizationHeader.substring(BEARER_PREFIX.length()).trim().split("\\.", -1);
		if (parts.length != 3) {
			return Optional.empty();
		}
		try {
			JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
			if (!claims.isObject()) {
				return Optional.empty();
			}
			String jti = idClaim(claims, "jti");
			String subject = idClaim(claims, "sub");
			if (jti == null && subject == null) {
				return Optional.empty();
			}
			JsonNode tv = claims.get("tv");
			return Optional.of(new AccessTokenClaims(jti, subject, tv != null && tv.isIntegralNumber() ? tv.asLong() : 0));
		}
		catch (IllegalArgumentException | JacksonException ex) {
			// Not base64url or not JSON: leave it to the service, which answers 401
			return Optional.empty();
		}
	}

	private static String idClaim(JsonNode claims, String name) {
		JsonNode node = claims.get(name);
		if (node == null || !node.isString()) {
			return null;
		}
		String value = node.asString();
		return value.isEmpty() || value.length() > MAX_ID_LENGTH ? null : value;
	}

}
