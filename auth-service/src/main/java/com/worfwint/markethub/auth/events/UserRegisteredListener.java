package com.worfwint.markethub.auth.events;

import java.util.Map;

import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.stereotype.Component;

import com.worfwint.markethub.auth.UserStore;

/**
 * Rebuilds {@link UserStore} from the compacted user.registered topic.
 * <p>
 * The store lives in memory, so on every start the listener reads the topic from the
 * beginning. Each instance uses its own consumer group, so every instance receives all
 * partitions instead of sharing them.
 */
@Component
public class UserRegisteredListener extends AbstractConsumerSeekAware {

	private final UserStore userStore;

	public UserRegisteredListener(UserStore userStore) {
		this.userStore = userStore;
	}

	@KafkaListener(topics = KafkaTopics.USER_REGISTERED, groupId = "auth-service-#{T(java.util.UUID).randomUUID()}")
	public void onUserRegistered(UserRegistered event) {
		userStore.save(event);
	}

	@Override
	public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
		super.onPartitionsAssigned(assignments, callback);
		callback.seekToBeginning(assignments.keySet());
	}

}
