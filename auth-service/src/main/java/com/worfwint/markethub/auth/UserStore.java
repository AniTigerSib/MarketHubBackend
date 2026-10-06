package com.worfwint.markethub.auth;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.worfwint.markethub.auth.events.UserRegistered;

/** Local copy of the users known to user-service, built from {@link UserRegistered} events. */
@Component
public class UserStore {

	private final Map<String, UserRegistered> users = new ConcurrentHashMap<>();

	public void save(UserRegistered user) {
		users.put(user.username(), user);
	}

	public Optional<UserRegistered> findByUsername(String username) {
		return Optional.ofNullable(users.get(username));
	}

}
