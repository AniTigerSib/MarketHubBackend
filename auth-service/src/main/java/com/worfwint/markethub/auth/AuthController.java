package com.worfwint.markethub.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.worfwint.markethub.auth.AuthDtos.LoginRequest;
import com.worfwint.markethub.auth.AuthDtos.RefreshRequest;
import com.worfwint.markethub.auth.AuthDtos.RegisterRequest;
import com.worfwint.markethub.auth.AuthDtos.RegisterResponse;
import com.worfwint.markethub.auth.AuthDtos.TokenResponse;
import com.worfwint.markethub.auth.account.Account;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
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

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@Valid @RequestBody RefreshRequest request) {
		authService.logout(request.refreshToken());
	}

}
