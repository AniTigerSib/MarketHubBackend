package com.worfwint.markethub.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AuthController {

	private final UserClient userClient;

	public AuthController(UserClient userClient) {
		this.userClient = userClient;
	}

	@PostMapping("/auth/login")
	public LoginResponse login(@RequestBody LoginRequest request) {
		UserDto user = userClient.findUser(request.username())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));

		// Placeholder token; replace with a real JWT later
		String token = UUID.randomUUID().toString();
		return new LoginResponse(token, user.username(), user.fullName());
	}

}
