package com.worfwint.markethub.auth.revocation;

import java.text.ParseException;
import java.util.Optional;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.worfwint.markethub.auth.jwt.JwtProperties;
import com.worfwint.markethub.auth.jwt.SigningKeys;

/**
 * Checks access tokens sent to auth-service itself (logout endpoints), the same way other
 * services do: signature against the published keys, issuer, and expiry. auth-service has
 * no Spring Security filter chain; only these endpoints need to know the caller.
 */
@Component
public class AccessTokenVerifier {

	private static final String BEARER_PREFIX = "Bearer ";

	private final NimbusJwtDecoder decoder;

	public AccessTokenVerifier(SigningKeys signingKeys, JwtProperties properties) {
		// Every published key, not just the active one, so tokens signed before a key rotation
		// still verify. Read on each call; logout is rare enough for that not to matter.
		JWKSource<SecurityContext> keys = (selector, context) -> {
			try {
				return selector.select(JWKSet.parse(signingKeys.publicJwks()));
			}
			catch (ParseException ex) {
				throw new KeySourceException("Published key set is invalid", ex);
			}
		};
		this.decoder = NimbusJwtDecoder.withJwkSource(keys).jwsAlgorithm(SignatureAlgorithm.RS256).build();
		this.decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
	}

	/**
	 * @param authorizationHeader the {@code Authorization} header value, may be null
	 * @return the token if the header holds a valid Bearer token, otherwise empty
	 */
	public Optional<Jwt> verify(String authorizationHeader) {
		if (authorizationHeader == null
				|| !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			return Optional.empty();
		}
		String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
		if (token.isEmpty()) {
			return Optional.empty();
		}
		try {
			return Optional.of(decoder.decode(token));
		}
		catch (JwtException ex) {
			return Optional.empty();
		}
	}

}
