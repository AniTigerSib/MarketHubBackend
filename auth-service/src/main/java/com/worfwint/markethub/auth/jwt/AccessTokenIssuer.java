package com.worfwint.markethub.auth.jwt;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.worfwint.markethub.auth.account.Account;

/** Signs short-lived RS256 access tokens. */
@Component
public class AccessTokenIssuer {

	private final JwtEncoder encoder;

	private final String keyId;

	private final JwtProperties properties;

	public AccessTokenIssuer(SigningKeys signingKeys, JwtProperties properties) {
		this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKeys.activeKey())));
		this.keyId = signingKeys.activeKey().getKeyID();
		this.properties = properties;
	}

	public String issue(Account account, Instant now) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.id(UUID.randomUUID().toString())
			.issuer(properties.issuer())
			.subject(account.id().toString())
			.issuedAt(now)
			.expiresAt(now.plus(properties.accessTokenTtl()))
			.claim("username", account.username())
			.claim("email", account.email())
			.claim("roles", account.roles())
			.build();
		// The "kid" header tells verifiers which published key to check the signature with
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

}
