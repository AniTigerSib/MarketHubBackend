package com.worfwint.markethub.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class OpenApiDevelopmentTests {

	private static HttpServer upstream;

	@DynamicPropertySource
	static void upstreamServices(DynamicPropertyRegistry properties) throws IOException {
		upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		upstream.createContext("/", exchange -> {
			boolean document = "/v3/api-docs".equals(exchange.getRequestURI().getPath());
			byte[] body = "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"Upstream API\"}}"
				.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(document ? 200 : 404, body.length);
			try (var output = exchange.getResponseBody()) {
				output.write(body);
			}
		});
		upstream.start();
		String address = "http://127.0.0.1:" + upstream.getAddress().getPort();
		properties.add("AUTH_SERVICE_URL", () -> address);
		properties.add("USER_SERVICE_URL", () -> address);
		properties.add("LISTING_SERVICE_URL", () -> address);
	}

	@AfterAll
	static void stopUpstream() {
		if (upstream != null) {
			upstream.stop(0);
		}
	}

	@Test
	void proxiesEachDefinitionToTheServiceDocumentPath() throws Exception {
		for (String service : new String[] { "auth", "users", "listings" }) {
			mvc.perform(get("/openapi/" + service))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("Upstream API"));
		}
	}

	@Autowired
	private MockMvc mvc;

	@Test
	void servesUiAndAllServiceDefinitions() throws Exception {
		mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
		mvc.perform(get("/v3/api-docs/swagger-config"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.urls.length()").value(3))
			.andExpect(jsonPath("$.urls[?(@.name == 'Auth')].url").value("/openapi/auth"))
			.andExpect(jsonPath("$.urls[?(@.name == 'Users')].url").value("/openapi/users"))
			.andExpect(jsonPath("$.urls[?(@.name == 'Listings')].url").value("/openapi/listings"));
	}
}
