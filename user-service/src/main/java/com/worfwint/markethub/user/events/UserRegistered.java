package com.worfwint.markethub.user.events;

/** Published to {@link KafkaTopics#USER_REGISTERED}, keyed by username. */
public record UserRegistered(String username, String fullName, String email) {
}
