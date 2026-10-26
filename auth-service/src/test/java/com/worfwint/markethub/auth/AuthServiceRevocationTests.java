package com.worfwint.markethub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.worfwint.markethub.auth.account.AccountRepository;
import com.worfwint.markethub.auth.events.AccountEventPublisher;
import com.worfwint.markethub.auth.jwt.AccessTokenIssuer;
import com.worfwint.markethub.auth.jwt.JwtProperties;
import com.worfwint.markethub.auth.revocation.TokenRevocations;
import com.worfwint.markethub.auth.revocation.TokenRevocations.RevocationUnavailableException;
import com.worfwint.markethub.auth.token.RefreshTokenRepository;

/** What logout does when Redis is down: refresh tokens are revoked, and the caller gets 503. */
class AuthServiceRevocationTests {

	private final AccountRepository accounts = mock(AccountRepository.class);

	private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);

	private final TokenRevocations revocations = mock(TokenRevocations.class);

	private final AuthService authService = new AuthService(accounts, refreshTokens, mock(AccessTokenIssuer.class),
			mock(PasswordEncoder.class), mock(AccountEventPublisher.class), mock(JwtProperties.class),
			new TransactionTemplate(mock(PlatformTransactionManager.class)), revocations);

	@Test
	void revokeAllSessionsRevokesRefreshTokensThenReports503() {
		UUID accountId = UUID.randomUUID();
		given(accounts.incrementTokenVersion(accountId)).willReturn(3);
		willThrow(unavailable()).given(revocations).publishTokenVersion(any(), anyInt());

		assertThatThrownBy(() -> authService.revokeAllSessions(accountId))
			.isInstanceOfSatisfying(ResponseStatusException.class,
					ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

		verify(refreshTokens).revokeAllForAccount(accountId);
		verify(revocations).publishTokenVersion(accountId, 3);
	}

	@Test
	void logoutRevokesRefreshTokenThenReports503() {
		Jwt jwt = Jwt.withTokenValue("access").header("alg", "RS256").jti("jti-1")
			.expiresAt(Instant.now().plusSeconds(600)).build();
		willThrow(unavailable()).given(revocations).revokeAccessToken(eq("jti-1"), any());

		assertThatThrownBy(() -> authService.logout("refresh", Optional.of(jwt)))
			.isInstanceOfSatisfying(ResponseStatusException.class,
					ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

		verify(refreshTokens).revokeByHash(any());
	}

	private static RevocationUnavailableException unavailable() {
		return new RevocationUnavailableException(new RedisConnectionFailureException("down"));
	}

}
