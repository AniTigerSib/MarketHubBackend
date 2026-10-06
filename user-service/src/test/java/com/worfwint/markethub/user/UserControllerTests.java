package com.worfwint.markethub.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.worfwint.markethub.user.events.UserEventPublisher;

@WebMvcTest(UserController.class)
@Import(UserRepository.class)
class UserControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserEventPublisher eventPublisher;

	@Test
	void returnsKnownUser() throws Exception {
		mockMvc.perform(get("/users/alice"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("alice"))
			.andExpect(jsonPath("$.fullName").value("Alice Smith"));
	}

	@Test
	void returns404ForUnknownUser() throws Exception {
		mockMvc.perform(get("/users/nobody"))
			.andExpect(status().isNotFound());
	}

	@Test
	void registersUserAndPublishesEvent() throws Exception {
		mockMvc.perform(post("/users")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\": \"bob\", \"fullName\": \"Bob\", \"email\": \"bob@markethub.local\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.username").value("bob"));

		verify(eventPublisher).publishRegistered(new User("bob", "Bob", "bob@markethub.local", null));
	}

	@Test
	void rejectsDuplicateUsername() throws Exception {
		mockMvc.perform(post("/users")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\": \"alice\", \"fullName\": \"Other\", \"email\": \"x@markethub.local\"}"))
			.andExpect(status().isConflict());

		verify(eventPublisher, never()).publishRegistered(any());
	}

}
