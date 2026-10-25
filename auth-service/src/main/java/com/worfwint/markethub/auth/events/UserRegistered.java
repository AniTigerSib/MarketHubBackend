package com.worfwint.markethub.auth.events;

import java.util.UUID;

/** Published to {@link KafkaTopics#USER_REGISTERED}, keyed by user id; consumed by user-service. */
public record UserRegistered(UUID userId, String username, String email, String displayName) {
}
