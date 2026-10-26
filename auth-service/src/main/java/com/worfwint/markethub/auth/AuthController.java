package com.worfwint.markethub.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.worfwint.markethub.auth.AuthDtos.LoginRequest;
import com.worfwint.markethub.auth.AuthDtos.RefreshRequest;
import com.worfwint.markethub.auth.AuthDtos.RegisterRequest;
import com.worfwint.markethub.auth.AuthDtos.RegisterResponse;
import com.worfwint.markethub.auth.AuthDtos.TokenResponse;
import com.worfwint.markethub.auth.account.Account;
import com.worfwint.markethub.auth.revocation.AccessTokenVerifier;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/auth")
public class AuthController {

	private final AuthService authService;

	private final AccessTokenVerifier accessTokens;

	public AuthController(AuthService authService, AccessTokenVerifier accessTokens) {
		this.authService = authService;
		this.accessTokens = accessTokens;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
		Account account = authService.register(request);
		return new RegisterResponse(account.id(), account.username(), account.email());
	}

	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request.login(), request.password());
	}

	@PostMapping("/refresh")
	public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return authService.refresh(request.refreshToken());
	}

	/**
	 * Ends this session. The access token in the {@code Authorization} header is optional; when
	 * it's sent and valid, it stops working immediately instead of at expiry.
	 */
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@Valid @RequestBody RefreshRequest request,
			@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		authService.logout(request.refreshToken(), accessTokens.verify(authorization));
	}

	/** Ends every session of the caller's account, on all devices. Needs a valid access token. */
	@PostMapping("/logout-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logoutAll(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		authService.logoutAll(accessTokens.verify(authorization)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid access token required")));
	}

}
