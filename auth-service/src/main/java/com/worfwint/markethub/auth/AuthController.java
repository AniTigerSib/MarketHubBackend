package com.worfwint.markethub.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.worfwint.markethub.auth.events.LoginEventPublisher;
import com.worfwint.markethub.auth.events.UserRegistered;

@RestController
public class AuthController {

	private final UserStore userStore;

	private final LoginEventPublisher eventPublisher;

	public AuthController(UserStore userStore, LoginEventPublisher eventPublisher) {
		this.userStore = userStore;
		this.eventPublisher = eventPublisher;
	}

	@PostMapping("/auth/login")
	public LoginResponse login(@RequestBody LoginRequest request) {
		UserRegistered user = userStore.findByUsername(request.username())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));

		eventPublisher.publishLoggedIn(user.username());

		// Placeholder token; replace with a real JWT later
		String token = UUID.randomUUID().toString();
		return new LoginResponse(token, user.username(), user.fullName());
	}

}
