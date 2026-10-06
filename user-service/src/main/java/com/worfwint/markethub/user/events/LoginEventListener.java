package com.worfwint.markethub.user.events;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.worfwint.markethub.user.UserRepository;

@Component
public class LoginEventListener {

	private final UserRepository userRepository;

	public LoginEventListener(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@KafkaListener(topics = KafkaTopics.USER_LOGGED_IN)
	public void onUserLoggedIn(UserLoggedIn event) {
		userRepository.recordLogin(event.username(), event.loggedInAt());
	}

}
