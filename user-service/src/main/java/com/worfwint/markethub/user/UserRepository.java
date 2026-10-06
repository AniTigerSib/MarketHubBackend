package com.worfwint.markethub.user;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** In-memory stand-in for a real user database. */
@Component
public class UserRepository {

	private final Map<String, User> users = new ConcurrentHashMap<>(Map.of(
			"michael", new User("michael", "Michael", "michael@markethub.local", null),
			"alice", new User("alice", "Alice Smith", "alice@markethub.local", null)));

	public Optional<User> findByUsername(String username) {
		return Optional.ofNullable(users.get(username));
	}

	public Collection<User> findAll() {
		return users.values();
	}

	/** Returns false if a user with this username already exists. */
	public boolean create(User user) {
		return users.putIfAbsent(user.username(), user) == null;
	}

	public void recordLogin(String username, Instant loggedInAt) {
		users.computeIfPresent(username, (name, user) -> user.withLastLoginAt(loggedInAt));
	}

}
