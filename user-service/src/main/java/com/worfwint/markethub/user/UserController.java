package com.worfwint.markethub.user;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.worfwint.markethub.user.events.UserEventPublisher;

@RestController
public class UserController {

	private final UserRepository userRepository;

	private final UserEventPublisher eventPublisher;

	public UserController(UserRepository userRepository, UserEventPublisher eventPublisher) {
		this.userRepository = userRepository;
		this.eventPublisher = eventPublisher;
	}

	@GetMapping("/users/{username}")
	public User getUser(@PathVariable String username) {
		return userRepository.findByUsername(username)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + username));
	}

	@PostMapping("/users")
	@ResponseStatus(HttpStatus.CREATED)
	public User register(@RequestBody RegisterUserRequest request) {
		User user = new User(request.username(), request.fullName(), request.email(), null);
		if (!userRepository.create(user)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists: " + request.username());
		}
		eventPublisher.publishRegistered(user);
		return user;
	}

}
