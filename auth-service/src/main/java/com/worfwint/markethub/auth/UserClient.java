package com.worfwint.markethub.auth;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** HTTP client for user-service. */
@Component
public class UserClient {

	private final RestClient restClient;

	public UserClient(@Value("${user-service.url}") String userServiceUrl) {
		this.restClient = RestClient.create(userServiceUrl);
	}

	public Optional<UserDto> findUser(String username) {
		try {
			return Optional.ofNullable(restClient.get()
				.uri("/users/{username}", username)
				.retrieve()
				.body(UserDto.class));
		}
		catch (HttpClientErrorException.NotFound ex) {
			return Optional.empty();
		}
	}

}
