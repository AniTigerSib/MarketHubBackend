package com.worfwint.markethub.auth;

public record LoginResponse(String token, String username, String fullName) {
}
