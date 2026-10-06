package com.worfwint.markethub.auth.events;

import java.time.Instant;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class LoginEventPublisher {

	private final KafkaTemplate<String, Object> kafkaTemplate;

	public LoginEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	public void publishLoggedIn(String username) {
		kafkaTemplate.send(KafkaTopics.USER_LOGGED_IN, username, new UserLoggedIn(username, Instant.now()));
	}

}
