package com.worfwint.markethub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.worfwint.markethub.auth.events.AccountEventPublisher;

/** Full HTTP flow against a real Postgres with Flyway migrations. Skipped without Docker. */
@SpringBootTest(properties = "spring.kafka.admin.auto-create=false")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthFlowIntegrationTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.1");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AccountEventPublisher events;

	@Test
	void logsInWithUsernameOrEmailAndTokenVerifiesAgainstPublishedKey() throws Exception {
		String username = uniqueUsername();
		String userId = register(username, username + "@markethub.local", "s3cret-pass");
		verify(events).publishRegistered(any(), eq(username));

		String byUsername = login(username.toUpperCase(), "s3cret-pass").andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsString();
		login(username + "@MarketHub.local", "s3cret-pass").andExpect(status().isOk());

		// Verify the way another service would: with only the public key from the JWKS endpoint
		String jwks = mockMvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
			.andExpect(jsonPath("$.keys[0].d").doesNotExist()) // no private key material
			.andReturn().getResponse().getContentAsString();
		RSAKey publicKey = JWKSet.parse(jwks).getKeys().getFirst().toRSAKey();
		Jwt jwt = NimbusJwtDecoder.withPublicKey(publicKey.toRSAPublicKey()).build()
			.decode(JsonPath.read(byUsername, "$.accessToken"));

		assertThat(jwt.getHeaders()).containsEntry("kid", publicKey.getKeyID());
		assertThat(jwt.getSubject()).isEqualTo(userId);
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("markethub-auth");
		assertThat(jwt.getClaimAsString("username")).isEqualTo(username);
		assertThat(jwt.getClaimAsStringList("roles")).isEqualTo(List.of("USER"));
	}

	@Test
	void rejectsWrongPasswordAndUnknownLogin() throws Exception {
		String username = uniqueUsername();
		register(username, username + "@markethub.local", "s3cret-pass");

		login(username, "wrong-pass").andExpect(status().isUnauthorized());
		login("nobody-" + username, "s3cret-pass").andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsDuplicateUsernameOrEmailIgnoringCase() throws Exception {
		String username = uniqueUsername();
		register(username, username + "@markethub.local", "s3cret-pass");

		registerRequest(username.toUpperCase(), "other-" + username + "@markethub.local").andExpect(status().isConflict());
		registerRequest("other-" + username, username.toUpperCase() + "@markethub.local").andExpect(status().isConflict());
	}

	@Test
	void rotatesRefreshTokenAndRevokesEverySessionWhenOldTokenIsReused() throws Exception {
		String username = uniqueUsername();
		register(username, username + "@markethub.local", "s3cret-pass");
		String first = refreshTokenOf(login(username, "s3cret-pass"));

		String second = refreshTokenOf(refresh(first).andExpect(status().isOk()));
		assertThat(second).isNotEqualTo(first);

		// Reusing the rotated token looks like theft: it fails and the newer token dies too
		refresh(first).andExpect(status().isUnauthorized());
		refresh(second).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutRevokesRefreshToken() throws Exception {
		String username = uniqueUsername();
		register(username, username + "@markethub.local", "s3cret-pass");
		String token = refreshTokenOf(login(username, "s3cret-pass"));

		mockMvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\": \"" + token + "\"}"))
			.andExpect(status().isNoContent());
		refresh(token).andExpect(status().isUnauthorized());
	}

	private static String uniqueUsername() {
		return "user" + UUID.randomUUID().toString().substring(0, 8);
	}

	private String register(String username, String email, String password) throws Exception {
		String body = mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\": \"%s\", \"email\": \"%s\", \"password\": \"%s\"}".formatted(username, email,
						password)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.userId");
	}

	private ResultActions registerRequest(String username, String email) throws Exception {
		return mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"%s\", \"email\": \"%s\", \"password\": \"s3cret-pass\"}".formatted(username, email)));
	}

	private ResultActions login(String login, String password) throws Exception {
		return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"login\": \"%s\", \"password\": \"%s\"}".formatted(login, password)));
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\": \"" + refreshToken + "\"}"));
	}

	private static String refreshTokenOf(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.refreshToken");
	}

}
