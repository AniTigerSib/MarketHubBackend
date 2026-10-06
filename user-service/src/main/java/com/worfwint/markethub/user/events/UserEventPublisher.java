package com.worfwint.markethub.user.events;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.worfwint.markethub.user.User;
import com.worfwint.markethub.user.UserRepository;

@Component
public class UserEventPublisher {

	private final KafkaTemplate<String, Object> kafkaTemplate;

	private final UserRepository userRepository;

	public UserEventPublisher(KafkaTemplate<String, Object> kafkaTemplate, UserRepository userRepository) {
		this.kafkaTemplate = kafkaTemplate;
		this.userRepository = userRepository;
	}

	public void publishRegistered(User user) {
		kafkaTemplate.send(KafkaTopics.USER_REGISTERED, user.username(),
				new UserRegistered(user.username(), user.fullName(), user.email()));
	}

	/** Publishes the seeded users; re-publishing is harmless because the topic is compacted by username. */
	@EventListener(ApplicationReadyEvent.class)
	public void publishExistingUsers() {
		userRepository.findAll().forEach(this::publishRegistered);
	}

}
