package com.worfwint.markethub.auth.events;

import java.time.Instant;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.worfwint.markethub.auth.account.Account;

@Component
public class AccountEventPublisher {

	private final KafkaTemplate<String, Object> kafkaTemplate;

	public AccountEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	public void publishRegistered(Account account, String displayName) {
		kafkaTemplate.send(KafkaTopics.USER_REGISTERED, account.id().toString(),
				new UserRegistered(account.id(), account.username(), account.email(), displayName));
	}

	public void publishLoggedIn(Account account, Instant loggedInAt) {
		kafkaTemplate.send(KafkaTopics.USER_LOGGED_IN, account.id().toString(),
				new UserLoggedIn(account.id(), account.username(), loggedInAt));
	}

}
