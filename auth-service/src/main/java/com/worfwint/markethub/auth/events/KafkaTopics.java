package com.worfwint.markethub.auth.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopics {

	public static final String USER_REGISTERED = "user.registered";

	public static final String USER_LOGGED_IN = "user.logged-in";

	// Both services declare both topics with the same settings, so either one can start first.
	// user.registered is compacted: Kafka keeps the latest event per user id.

	@Bean
	NewTopic userRegisteredTopic() {
		return TopicBuilder.name(USER_REGISTERED).partitions(3).replicas(1).compact().build();
	}

	@Bean
	NewTopic userLoggedInTopic() {
		return TopicBuilder.name(USER_LOGGED_IN).partitions(3).replicas(1).build();
	}

}
