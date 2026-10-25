package com.worfwint.markethub.auth.events;

import java.time.Instant;
import java.util.UUID;

/** Published to {@link KafkaTopics#USER_LOGGED_IN}, keyed by user id. */
public record UserLoggedIn(UUID userId, String username, Instant loggedInAt) {
}
