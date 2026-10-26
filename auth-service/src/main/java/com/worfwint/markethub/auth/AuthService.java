package com.worfwint.markethub.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.worfwint.markethub.auth.AuthDtos.RegisterRequest;
import com.worfwint.markethub.auth.AuthDtos.TokenResponse;
import com.worfwint.markethub.auth.account.Account;
import com.worfwint.markethub.auth.account.AccountRepository;
import com.worfwint.markethub.auth.events.AccountEventPublisher;
import com.worfwint.markethub.auth.jwt.AccessTokenIssuer;
import com.worfwint.markethub.auth.jwt.JwtProperties;
import com.worfwint.markethub.auth.revocation.TokenRevocations;
import com.worfwint.markethub.auth.revocation.TokenRevocations.RevocationUnavailableException;
import com.worfwint.markethub.auth.token.RefreshToken;
import com.worfwint.markethub.auth.token.RefreshTokenRepository;
import com.worfwint.markethub.auth.token.RefreshTokens;

@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	private static final String DEFAULT_ROLE = "USER";

	private final AccountRepository accounts;

	private final RefreshTokenRepository refreshTokens;

	private final AccessTokenIssuer accessTokenIssuer;

	private final PasswordEncoder passwordEncoder;

	private final AccountEventPublisher events;

	private final JwtProperties jwtProperties;

	private final TransactionTemplate tx;

	private final TokenRevocations revocations;

	/** Checked against when the login is unknown, so both failure cases take the same time. */
	private final String dummyPasswordHash;

	public AuthService(AccountRepository accounts, RefreshTokenRepository refreshTokens,
			AccessTokenIssuer accessTokenIssuer, PasswordEncoder passwordEncoder, AccountEventPublisher events,
			JwtProperties jwtProperties, TransactionTemplate tx, TokenRevocations revocations) {
		this.accounts = accounts;
		this.refreshTokens = refreshTokens;
		this.accessTokenIssuer = accessTokenIssuer;
		this.passwordEncoder = passwordEncoder;
		this.events = events;
		this.jwtProperties = jwtProperties;
		this.tx = tx;
		this.revocations = revocations;
		this.dummyPasswordHash = passwordEncoder.encode("dummy-password");
	}

	public Account register(RegisterRequest request) {
		if (accounts.existsByUsername(request.username())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already taken");
		}
		if (accounts.existsByEmail(request.email())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
		}

		UUID id = UUID.randomUUID();
		String passwordHash = passwordEncoder.encode(request.password());
		try {
			tx.executeWithoutResult(status -> {
				accounts.insert(id, request.username(), request.email(), passwordHash);
				accounts.addRole(id, DEFAULT_ROLE);
			});
		}
		catch (DuplicateKeyException ex) {
			// Someone registered the same username or email between the checks and the insert
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Username or email is already taken");
		}

		Account account = accounts.findById(id).orElseThrow();
		String displayName = request.displayName() == null || request.displayName().isBlank()
				? request.username() : request.displayName();
		// Published after the commit, so user-service never sees an account that was rolled back
		events.publishRegistered(account, displayName);
		return account;
	}

	public TokenResponse login(String login, String password) {
		Optional<Account> found = accounts.findByLogin(login);
		if (found.isEmpty()) {
			passwordEncoder.matches(password, dummyPasswordHash);
			throw invalidCredentials();
		}
		Account account = found.get();
		if (!passwordEncoder.matches(password, account.passwordHash()) || !account.enabled()) {
			throw invalidCredentials();
		}

		Instant now = Instant.now();
		String refreshToken = tx.execute(status -> {
			accounts.updateLastLogin(account.id(), now);
			return createRefreshToken(account.id(), now);
		});
		events.publishLoggedIn(account, now);
		return tokens(account, refreshToken, now);
	}

	/**
	 * Swaps a refresh token for a new pair; each refresh token works once. Presenting an
	 * already-rotated token means it was probably stolen, so every session of that account
	 * is revoked, access tokens included.
	 */
	public TokenResponse refresh(String refreshToken) {
		Instant now = Instant.now();
		byte[] hash = RefreshTokens.hash(refreshToken);
		RefreshOutcome outcome = tx.execute(status -> {
			Optional<UUID> owner = refreshTokens.findAccountIdByHash(hash);
			// Account row first, then the token row: the same order as revokeSessions, so a
			// refresh racing with "log out everywhere" can't leave a new session behind
			if (owner.isEmpty() || !accounts.lockForUpdate(owner.get())) {
				return RefreshOutcome.REJECTED;
			}
			Optional<RefreshToken> found = refreshTokens.findByHashForUpdate(hash);
			if (found.isEmpty()) {
				return RefreshOutcome.REJECTED;
			}
			RefreshToken stored = found.get();
			if (stored.isRotated()) {
				int version = revokeSessions(stored.accountId());
				log.warn("Rotated refresh token reused for account {}; revoked all its sessions", stored.accountId());
				return new RefreshOutcome(null, stored.accountId(), version);
			}
			if (stored.isRevoked() || stored.isExpired(now)) {
				return RefreshOutcome.REJECTED;
			}
			Optional<Account> account = accounts.findById(stored.accountId()).filter(Account::enabled);
			if (account.isEmpty()) {
				return RefreshOutcome.REJECTED;
			}

			String newToken = createRefreshToken(stored.accountId(), now);
			refreshTokens.markRotated(stored.id(), idOf(newToken));
			return new RefreshOutcome(tokens(account.get(), newToken, now), null, 0);
		});
		// Handled outside the transaction so the reuse revocation above is committed
		if (outcome.revokedAccount() != null) {
			try {
				revocations.publishTokenVersion(outcome.revokedAccount(), outcome.newVersion());
			}
			catch (RevocationUnavailableException ex) {
				// The caller gets 401 either way; refresh tokens are already revoked
				log.error("Couldn't revoke access tokens of account {} after refresh-token reuse",
						outcome.revokedAccount(), ex);
			}
		}
		if (outcome.response() == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
		}
		return outcome.response();
	}

	/**
	 * Revokes the refresh token and, if a valid access token is given, blacklists it too.
	 * Other sessions of the account stay logged in.
	 *
	 * @param accessToken the caller's verified access token, if it sent one
	 */
	public void logout(String refreshToken, Optional<Jwt> accessToken) {
		refreshTokens.revokeByHash(RefreshTokens.hash(refreshToken));
		accessToken.ifPresent(jwt -> {
			Duration remaining = Duration.between(Instant.now(), jwt.getExpiresAt());
			try {
				revocations.revokeAccessToken(jwt.getId(), remaining);
			}
			catch (RevocationUnavailableException ex) {
				throw blacklistUnavailable(ex);
			}
		});
	}

	/**
	 * "Log out everywhere": revokes every refresh and access token of the token's account.
	 * A token that is itself already revoked by an earlier version bump is refused, so an old
	 * stolen token can't be used to log the owner out.
	 */
	public void logoutAll(Jwt accessToken) {
		UUID accountId = UUID.fromString(accessToken.getSubject());
		boolean current = accounts.findById(accountId)
			.filter(Account::enabled)
			.filter(account -> tokenVersionOf(accessToken) >= account.tokenVersion())
			.isPresent();
		if (!current) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid access token");
		}
		revokeAllSessions(accountId);
	}

	/**
	 * Revokes every refresh token of the account and every access token issued so far. For
	 * "log out everywhere", and later password change or ban.
	 *
	 * @throws ResponseStatusException 503 if the access-token blacklist couldn't be updated;
	 *         the refresh tokens are revoked by then, so retrying is safe
	 */
	public void revokeAllSessions(UUID accountId) {
		int version = tx.execute(status -> revokeSessions(accountId));
		try {
			revocations.publishTokenVersion(accountId, version);
		}
		catch (RevocationUnavailableException ex) {
			throw blacklistUnavailable(ex);
		}
	}

	/** Must run in a transaction; takes the account row lock before touching tokens. */
	private int revokeSessions(UUID accountId) {
		int version = accounts.incrementTokenVersion(accountId);
		refreshTokens.revokeAllForAccount(accountId);
		return version;
	}

	/** Tokens issued before token versions existed have no "tv" claim: version 0. */
	private static long tokenVersionOf(Jwt jwt) {
		Object claim = jwt.getClaim(AccessTokenIssuer.TOKEN_VERSION_CLAIM);
		return claim instanceof Number number ? number.longValue() : 0;
	}

	private static ResponseStatusException blacklistUnavailable(RevocationUnavailableException ex) {
		log.error("Couldn't update the access-token blacklist", ex);
		return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Logout is temporarily unavailable, try again");
	}

	private String createRefreshToken(UUID accountId, Instant now) {
		String token = RefreshTokens.generate();
		refreshTokens.insert(idOf(token), accountId, RefreshTokens.hash(token), now.plus(jwtProperties.refreshTokenTtl()));
		return token;
	}

	/** Row id derived from the token, so the rotated row can point at its successor without another lookup. */
	private static UUID idOf(String token) {
		return UUID.nameUUIDFromBytes(RefreshTokens.hash(token));
	}

	private TokenResponse tokens(Account account, String refreshToken, Instant now) {
		return new TokenResponse(accessTokenIssuer.issue(account, now), refreshToken, "Bearer",
				jwtProperties.accessTokenTtl().toSeconds());
	}

	private static ResponseStatusException invalidCredentials() {
		return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid login or password");
	}

	/** Result of the refresh transaction: new tokens, or which account to revoke afterwards. */
	private record RefreshOutcome(TokenResponse response, UUID revokedAccount, int newVersion) {

		static final RefreshOutcome REJECTED = new RefreshOutcome(null, null, 0);

	}

}
