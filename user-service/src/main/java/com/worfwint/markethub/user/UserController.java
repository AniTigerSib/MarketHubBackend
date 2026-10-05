package com.worfwint.markethub.user;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class UserController {

	// In-memory stand-in for a real user database
	private final Map<String, User> users = Map.of(
			"michael", new User("michael", "Michael", "michael@markethub.local"),
			"alice", new User("alice", "Alice Smith", "alice@markethub.local"));

	@GetMapping("/users/{username}")
	public User getUser(@PathVariable String username) {
		User user = users.get(username);
		if (user == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + username);
		}
		return user;
	}

}
