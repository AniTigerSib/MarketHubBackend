package com.worfwint.markethub.auth;

/** User data as returned by user-service. */
public record UserDto(String username, String fullName, String email) {
}
