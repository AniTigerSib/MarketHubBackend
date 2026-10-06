package com.worfwint.markethub.user.events;

import java.time.Instant;

/** Consumed from {@link KafkaTopics#USER_LOGGED_IN}; published by auth-service. */
public record UserLoggedIn(String username, Instant loggedInAt) {
}
