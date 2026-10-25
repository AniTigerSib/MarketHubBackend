package com.worfwint.markethub.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
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

	/** Checked against when the login is unknown, so both failure cases take the same time. */
	private final String dummyPasswordHash;

	public AuthService(AccountRepository accounts, RefreshTokenRepository refreshTokens,
			AccessTokenIssuer accessTokenIssuer, PasswordEncoder passwordEncoder, AccountEventPublisher events,
			JwtProperties jwtProperties, TransactionTemplate tx) {
		this.accounts = accounts;
		this.refreshTokens = refreshTokens;
		this.accessTokenIssuer = accessTokenIssuer;
		this.passwordEncoder = passwordEncoder;
		this.events = events;
		this.jwtProperties = jwtProperties;
		this.tx = tx;
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
	 * is revoked.
	 */
	public TokenResponse refresh(String refreshToken) {
		Instant now = Instant.now();
		TokenResponse response = tx.execute(status -> {
			Optional<RefreshToken> found = refreshTokens.findByHashForUpdate(RefreshTokens.hash(refreshToken));
			if (found.isEmpty()) {
				return null;
			}
			RefreshToken stored = found.get();
			if (stored.isRotated()) {
				int revoked = refreshTokens.revokeAllForAccount(stored.accountId());
				log.warn("Rotated refresh token reused for account {}; revoked {} active sessions",
						stored.accountId(), revoked);
				return null;
			}
			if (stored.isRevoked() || stored.isExpired(now)) {
				return null;
			}
			Optional<Account> account = accounts.findById(stored.accountId()).filter(Account::enabled);
			if (account.isEmpty()) {
				return null;
			}

			String newToken = createRefreshToken(stored.accountId(), now);
			refreshTokens.markRotated(stored.id(), idOf(newToken));
			return tokens(account.get(), newToken, now);
		});
		// Thrown outside the transaction so the reuse revocation above is committed
		if (response == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
		}
		return response;
	}

	/** Revokes the refresh token. Access tokens already issued stay valid until they expire. */
	public void logout(String refreshToken) {
		refreshTokens.revokeByHash(RefreshTokens.hash(refreshToken));
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

}
