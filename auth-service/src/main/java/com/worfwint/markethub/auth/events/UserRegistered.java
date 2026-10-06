package com.worfwint.markethub.auth.events;

/** Consumed from {@link KafkaTopics#USER_REGISTERED}; published by user-service. */
public record UserRegistered(String username, String fullName, String email) {
}
