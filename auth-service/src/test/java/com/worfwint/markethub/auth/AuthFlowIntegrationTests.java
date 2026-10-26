package com.worfwint.markethub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.worfwint.markethub.auth.events.AccountEventPublisher;

/** Full HTTP flow against real Postgres (Flyway migrations) and Redis. Skipped without Docker. */
@SpringBootTest(properties = "spring.kafka.admin.auto-create=false")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthFlowIntegrationTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.1");

	@Container
	@ServiceConnection(name = "redis")
	static GenericContainer<?> redis = new GenericContainer<>("redis:8.2").withExposedPorts(6379);

	@Autowired
	private StringRedisTemplate redisTemplate;

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
		assertThat(jwt.getClaims()).containsEntry("tv", 0L);
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

	@Test
	void logoutWithAccessTokenBlacklistsItUntilItExpires() throws Exception {
		String username = uniqueUsername();
		register(username, username + "@markethub.local", "s3cret-pass");
		ResultActions session = login(username, "s3cret-pass");
		String accessToken = accessTokenOf(session);
		String jti = JsonPath.read(claimsOf(accessToken), "$.jti");

		mockMvc.perform(post("/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\": \"" + refreshTokenOf(session) + "\"}"))
			.andExpect(status().isNoContent());

		assertThat(redisTemplate.opsForValue().get("revoked:jti:" + jti)).isEqualTo("1");
		assertThat(redisTemplate.getExpire("revoked:jti:" + jti)).isBetween(800L, 900L);
	}

	@Test
	void logoutIgnoresAnInvalidAccessToken() throws Exception {
		String username = uniqueUsername();
		register(username, username + "@markethub.local", "s3cret-pass");
		String token = refreshTokenOf(login(username, "s3cret-pass"));

		mockMvc.perform(post("/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
				.contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\": \"" + token + "\"}"))
			.andExpect(status().isNoContent());
		refresh(token).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutAllEndsEverySessionAndNewLoginsGetTheNewVersion() throws Exception {
		String username = uniqueUsername();
		String userId = register(username, username + "@markethub.local", "s3cret-pass");
		ResultActions laptop = login(username, "s3cret-pass");
		ResultActions phone = login(username, "s3cret-pass");
		String laptopAccess = accessTokenOf(laptop);

		logoutAll(laptopAccess).andExpect(status().isNoContent());

		refresh(refreshTokenOf(laptop)).andExpect(status().isUnauthorized());
		refresh(refreshTokenOf(phone)).andExpect(status().isUnauthorized());
		assertThat(redisTemplate.opsForValue().get("tv:" + userId)).isEqualTo("1");
		assertThat(redisTemplate.getExpire("tv:" + userId)).isBetween(800L, 900L);

		// A new login carries the new version, and a refresh keeps it
		ResultActions again = login(username, "s3cret-pass");
		assertThat((Integer) JsonPath.read(claimsOf(accessTokenOf(again)), "$.tv")).isEqualTo(1);
		String refreshed = accessTokenOf(refresh(refreshTokenOf(again)).andExpect(status().isOk()));
		assertThat((Integer) JsonPath.read(claimsOf(refreshed), "$.tv")).isEqualTo(1);

		// The revoked token can't trigger another "log out everywhere"
		logoutAll(laptopAccess).andExpect(status().isUnauthorized());
		logoutAll(refreshed).andExpect(status().isNoContent());
		assertThat(redisTemplate.opsForValue().get("tv:" + userId)).isEqualTo("2");
	}

	@Test
	void logoutAllNeedsAValidAccessToken() throws Exception {
		mockMvc.perform(post("/auth/logout-all")).andExpect(status().isUnauthorized());
		logoutAll("not-a-jwt").andExpect(status().isUnauthorized());
	}

	@Test
	void refreshTokenReuseAlsoRevokesAccessTokens() throws Exception {
		String username = uniqueUsername();
		String userId = register(username, username + "@markethub.local", "s3cret-pass");
		String first = refreshTokenOf(login(username, "s3cret-pass"));
		refresh(first).andExpect(status().isOk());

		refresh(first).andExpect(status().isUnauthorized());

		assertThat(redisTemplate.opsForValue().get("tv:" + userId)).isEqualTo("1");
	}

	private ResultActions logoutAll(String accessToken) throws Exception {
		return mockMvc.perform(post("/auth/logout-all").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
	}

	private static String accessTokenOf(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.accessToken");
	}

	/** The JWT payload as JSON, without verifying it. */
	private static String claimsOf(String jwt) {
		return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
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
