package com.worfwint.markethub.user.events;

import java.util.UUID;

/** Consumed from {@link KafkaTopics#USER_REGISTERED}; published by auth-service. */
public record UserRegistered(UUID userId, String username, String email, String displayName) {
}
