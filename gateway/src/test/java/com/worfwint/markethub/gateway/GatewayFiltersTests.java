package com.worfwint.markethub.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static com.worfwint.markethub.gateway.TestTokens.bearer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full gateway context, no services and <b>no Redis</b> (the port is closed): CORS, the
 * in-memory rate-limit fallback, and the blacklist check letting tokens through. A rule on an
 * unrouted path lets us check the rate limiter without proxying anywhere.
 */
@SpringBootTest(properties = {
		"spring.data.redis.port=1",
		"markethub.cors.allowed-origins=http://ui.test",
		"markethub.rate-limit.rules[0].name=probe",
		"markethub.rate-limit.rules[0].method=GET",
		"markethub.rate-limit.rules[0].path=/probe",
		"markethub.rate-limit.rules[0].capacity=1",
		"markethub.rate-limit.rules[0].window=1m"
})
@AutoConfigureMockMvc
class GatewayFiltersTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void answersPreflightForAllowedOrigin() throws Exception {
		mvc.perform(options("/listings")
						.header("Origin", "http://ui.test")
						.header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", "Authorization, Content-Type"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://ui.test"))
				.andExpect(header().string("Access-Control-Max-Age", "3600"));
	}

	@Test
	void refusesPreflightForUnknownOrigin() throws Exception {
		mvc.perform(options("/listings")
						.header("Origin", "http://evil.test")
						.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	void rateLimitedResponseIsReadableByTheBrowser() throws Exception {
		mvc.perform(get("/probe").header("Origin", "http://ui.test"));

		mvc.perform(get("/probe").header("Origin", "http://ui.test"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(header().string("Access-Control-Allow-Origin", "http://ui.test"))
				.andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("Retry-After")));
	}

	@Test
	void letsTokensThroughWhenRedisIsDown() throws Exception {
		// No route for /listings-probe: 404 means the gateway let the request through
		mvc.perform(get("/listings-probe").header(HttpHeaders.AUTHORIZATION, bearer("{\"jti\":\"j\",\"sub\":\"u\",\"tv\":0}")))
			.andExpect(status().isNotFound());
	}

}
