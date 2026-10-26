package com.worfwint.markethub.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTests {

	private final RateLimitFilter filter = new RateLimitFilter(
			new RateLimitProperties(true, List.of(
					new RateLimitProperties.Rule("login", "post", "/auth/login", 2, Duration.ofMinutes(1)))),
			new RateLimiter());

	@Test
	void rejectsThirdLoginFromSameIpWith429() throws Exception {
		assertThat(send("POST", "/auth/login", "10.0.0.1").getStatus()).isEqualTo(200);
		assertThat(send("POST", "/auth/login", "10.0.0.1").getStatus()).isEqualTo(200);

		MockHttpServletResponse rejected = send("POST", "/auth/login", "10.0.0.1");

		assertThat(rejected.getStatus()).isEqualTo(429);
		assertThat(rejected.getHeader("Retry-After")).isEqualTo("30");
		assertThat(rejected.getContentType()).startsWith("application/json");
		assertThat(rejected.getContentAsString())
				.isEqualTo("{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Too many requests. Try again in 30 seconds.\"}");
	}

	@Test
	void countsEachIpSeparately() throws Exception {
		send("POST", "/auth/login", "10.0.0.1");
		send("POST", "/auth/login", "10.0.0.1");

		assertThat(send("POST", "/auth/login", "10.0.0.2").getStatus()).isEqualTo(200);
	}

	@Test
	void ignoresOtherMethodsAndPaths() throws Exception {
		for (int i = 0; i < 5; i++) {
			assertThat(send("GET", "/auth/login", "10.0.0.1").getStatus()).isEqualTo(200);
			assertThat(send("POST", "/auth/logout", "10.0.0.1").getStatus()).isEqualTo(200);
			assertThat(send("POST", "/auth/login/extra", "10.0.0.1").getStatus()).isEqualTo(200);
		}
	}

	private MockHttpServletResponse send(String method, String path, String ip) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setServletPath(path);
		request.setRemoteAddr(ip);
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}
}
