package com.worfwint.markethub.auth.events;

import java.time.Instant;

/** Published to {@link KafkaTopics#USER_LOGGED_IN}, keyed by username. */
public record UserLoggedIn(String username, Instant loggedInAt) {
}
