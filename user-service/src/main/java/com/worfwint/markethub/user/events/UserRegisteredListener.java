package com.worfwint.markethub.user.events;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.worfwint.markethub.user.UserRepository;

/** Creates a profile for each account registered in auth-service. */
@Component
public class UserRegisteredListener {

	private final UserRepository userRepository;

	public UserRegisteredListener(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@KafkaListener(topics = KafkaTopics.USER_REGISTERED)
	public void onUserRegistered(UserRegistered event) {
		userRepository.upsertIdentity(event.userId(), event.username(), event.email(), event.displayName());
	}

}
