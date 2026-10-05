package com.worfwint.markethub.auth;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
class AuthControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserClient userClient;

	@Test
	void logsInKnownUser() throws Exception {
		given(userClient.findUser("alice"))
			.willReturn(Optional.of(new UserDto("alice", "Alice Smith", "alice@markethub.local")));

		mockMvc.perform(post("/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\": \"alice\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("alice"))
			.andExpect(jsonPath("$.fullName").value("Alice Smith"))
			.andExpect(jsonPath("$.token").isNotEmpty());
	}

	@Test
	void rejectsUnknownUser() throws Exception {
		given(userClient.findUser("nobody")).willReturn(Optional.empty());

		mockMvc.perform(post("/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\": \"nobody\"}"))
			.andExpect(status().isUnauthorized());
	}

}
