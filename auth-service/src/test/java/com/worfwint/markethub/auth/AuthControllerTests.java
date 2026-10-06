package com.worfwint.markethub.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.worfwint.markethub.auth.events.LoginEventPublisher;
import com.worfwint.markethub.auth.events.UserRegistered;

@WebMvcTest(AuthController.class)
@Import(UserStore.class)
class AuthControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserStore userStore;

	@MockitoBean
	private LoginEventPublisher eventPublisher;

	@Test
	void logsInKnownUserAndPublishesEvent() throws Exception {
		userStore.save(new UserRegistered("alice", "Alice Smith", "alice@markethub.local"));

		mockMvc.perform(post("/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\": \"alice\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("alice"))
			.andExpect(jsonPath("$.fullName").value("Alice Smith"))
			.andExpect(jsonPath("$.token").isNotEmpty());

		verify(eventPublisher).publishLoggedIn("alice");
	}

	@Test
	void rejectsUnknownUser() throws Exception {
		mockMvc.perform(post("/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\": \"nobody\"}"))
			.andExpect(status().isUnauthorized());

		verify(eventPublisher, never()).publishLoggedIn(any());
	}

}
