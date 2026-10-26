package com.worfwint.markethub.gateway;

import static com.worfwint.markethub.gateway.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Gateway with a real Redis: the blacklist check and Redis-backed rate limits. No services run;
 * requests that get through end in 404 (no route), which is how "let through" shows here.
 * Skipped without Docker.
 */
@SpringBootTest(properties = {
		"markethub.cors.allowed-origins=http://ui.test",
		"markethub.rate-limit.rules[0].name=probe",
		"markethub.rate-limit.rules[0].method=GET",
		"markethub.rate-limit.rules[0].path=/limited",
		"markethub.rate-limit.rules[0].capacity=2",
		"markethub.rate-limit.rules[0].window=1m"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class GatewayRedisIntegrationTests {

	@Container
	@ServiceConnection(name = "redis")
	static GenericContainer<?> redis = new GenericContainer<>("redis:8.2").withExposedPorts(6379);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Test
	void rejectsLoggedOutToken() throws Exception {
		String jti = UUID.randomUUID().toString();
		redisTemplate.opsForValue().set("revoked:jti:" + jti, "1", Duration.ofMinutes(1));

		mvc.perform(get("/probe").header("Origin", "http://ui.test")
				.header(HttpHeaders.AUTHORIZATION, bearer("{\"jti\":\"" + jti + "\",\"sub\":\"u1\",\"tv\":0}")))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer error=\"invalid_token\"")))
			.andExpect(header().string("Access-Control-Allow-Origin", "http://ui.test"))
			.andExpect(jsonPath("$.status").value(401));
	}

	@Test
	void rejectsTokensOlderThanTheUsersTokenVersion() throws Exception {
		String user = UUID.randomUUID().toString();
		redisTemplate.opsForValue().set("tv:" + user, "2", Duration.ofMinutes(1));

		probeWith("{\"jti\":\"a\",\"sub\":\"" + user + "\",\"tv\":1}").andExpect(status().isUnauthorized());
		probeWith("{\"jti\":\"b\",\"sub\":\"" + user + "\"}").andExpect(status().isUnauthorized());
		probeWith("{\"jti\":\"c\",\"sub\":\"" + user + "\",\"tv\":2}").andExpect(status().isNotFound());
		probeWith("{\"jti\":\"d\",\"sub\":\"" + user + "\",\"tv\":3}").andExpect(status().isNotFound());
	}

	@Test
	void letsThroughTokensWithNothingRevoked() throws Exception {
		probeWith("{\"jti\":\"" + UUID.randomUUID() + "\",\"sub\":\"" + UUID.randomUUID() + "\",\"tv\":0}")
			.andExpect(status().isNotFound());
		mvc.perform(get("/probe")).andExpect(status().isNotFound());
		mvc.perform(get("/probe").header(HttpHeaders.AUTHORIZATION, "Bearer garbage")).andExpect(status().isNotFound());
	}

	@Test
	void keepsRateLimitCountersInRedis() throws Exception {
		mvc.perform(get("/limited")).andExpect(status().isNotFound());
		mvc.perform(get("/limited")).andExpect(status().isNotFound());

		mvc.perform(get("/limited"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", "30"));

		assertThat(redisTemplate.hasKey("rl:probe|127.0.0.1")).isTrue();
		assertThat(redisTemplate.getExpire("rl:probe|127.0.0.1")).isBetween(1L, 60L);
	}

	private ResultActions probeWith(String claims) throws Exception {
		return mvc.perform(get("/probe").header(HttpHeaders.AUTHORIZATION, bearer(claims)));
	}

}
