package com.worfwint.markethub.gateway.revocation;

import static com.worfwint.markethub.gateway.TestTokens.bearer;
import static com.worfwint.markethub.gateway.TestTokens.token;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccessTokenClaimsTests {

	@Test
	void readsJtiSubjectAndVersion() {
		assertThat(AccessTokenClaims.fromAuthorizationHeader(bearer("{\"jti\":\"j1\",\"sub\":\"u1\",\"tv\":3}")))
			.contains(new AccessTokenClaims("j1", "u1", 3));
	}

	@Test
	void tokenWithoutVersionCountsAsVersionZero() {
		assertThat(AccessTokenClaims.fromAuthorizationHeader(bearer("{\"jti\":\"j1\",\"sub\":\"u1\"}")))
			.contains(new AccessTokenClaims("j1", "u1", 0));
		assertThat(AccessTokenClaims.fromAuthorizationHeader(bearer("{\"jti\":\"j1\",\"sub\":\"u1\",\"tv\":\"9\"}")))
			.contains(new AccessTokenClaims("j1", "u1", 0));
	}

	@Test
	void prefixIsCaseInsensitive() {
		assertThat(AccessTokenClaims.fromAuthorizationHeader("bearer " + token("{\"jti\":\"j1\"}"))).isPresent();
	}

	@Test
	void ignoresAnythingThatIsNotAReadableBearerJwt() {
		assertThat(AccessTokenClaims.fromAuthorizationHeader(null)).isEmpty();
		assertThat(AccessTokenClaims.fromAuthorizationHeader("Basic dXNlcjpwYXNz")).isEmpty();
		assertThat(AccessTokenClaims.fromAuthorizationHeader("Bearer opaque-token")).isEmpty();
		assertThat(AccessTokenClaims.fromAuthorizationHeader("Bearer a.!!!.c")).isEmpty();
		assertThat(AccessTokenClaims.fromAuthorizationHeader(bearer("not json"))).isEmpty();
		assertThat(AccessTokenClaims.fromAuthorizationHeader(bearer("[1,2]"))).isEmpty();
		assertThat(AccessTokenClaims.fromAuthorizationHeader(bearer("{\"tv\":1}"))).isEmpty();
	}

	@Test
	void ignoresIdsThatAreNotShortStrings() {
		String longId = "x".repeat(65);
		assertThat(AccessTokenClaims.fromAuthorizationHeader(bearer("{\"jti\":\"" + longId + "\",\"sub\":\"u1\"}")))
			.contains(new AccessTokenClaims(null, "u1", 0));
		assertThat(AccessTokenClaims.fromAuthorizationHeader(bearer("{\"jti\":1,\"sub\":{}}"))).isEmpty();
	}

}
