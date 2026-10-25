package com.worfwint.markethub.auth;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of {@link AuthController}. */
public final class AuthDtos {

	private AuthDtos() {
	}

	public record RegisterRequest(
			@NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{3,32}$",
					message = "must be 3-32 characters: letters, digits, '_', '.' or '-'") String username,
			@NotBlank @Email @Size(max = 254) String email,
			// BCrypt only uses the first 72 bytes
			@NotBlank @Size(min = 8, max = 72) String password,
			/** Optional; defaults to the username. */
			@Size(max = 64) String displayName) {
	}

	public record RegisterResponse(UUID userId, String username, String email) {
	}

	/** {@code login} is either the username or the email. */
	public record LoginRequest(@NotBlank String login, @NotBlank String password) {
	}

	public record RefreshRequest(@NotBlank String refreshToken) {
	}

	public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {
	}

}
