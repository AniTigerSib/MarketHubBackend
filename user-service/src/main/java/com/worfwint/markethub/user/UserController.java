package com.worfwint.markethub.user;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.worfwint.markethub.user.UserDtos.PublicProfile;
import com.worfwint.markethub.user.UserDtos.UpdateProfileRequest;

import jakarta.validation.Valid;

@RestController
public class UserController {

	private final UserRepository userRepository;

	public UserController(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	/** Public profile; no token needed. */
	@GetMapping("/users/{username}")
	public PublicProfile getUser(@PathVariable String username) {
		return userRepository.findByUsername(username)
			.map(PublicProfile::of)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + username));
	}

	/** Public profile by user id, e.g. a listing's seller (listings store the seller's id). */
	@GetMapping("/users/id/{id}")
	public PublicProfile getUserById(@PathVariable UUID id) {
		return userRepository.findById(id)
			.map(PublicProfile::of)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + id));
	}

	/** The caller's full profile. The token's subject is the user id. */
	@GetMapping("/users/me")
	public UserProfile getMe(@AuthenticationPrincipal Jwt jwt) {
		return userRepository.findById(userId(jwt)).orElseThrow(UserController::profileNotFound);
	}

	@PutMapping("/users/me")
	public UserProfile updateMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
		return userRepository.updateProfile(userId(jwt), request.displayName(), request.avatarUrl(), request.phone())
			.orElseThrow(UserController::profileNotFound);
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	// Can happen briefly after registration, until the user.registered event is consumed
	private static ResponseStatusException profileNotFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found");
	}

}
